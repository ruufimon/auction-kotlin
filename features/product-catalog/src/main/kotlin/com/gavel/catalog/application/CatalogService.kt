package com.gavel.catalog.application

import com.gavel.bidding.api.AuctionRegistry
import com.gavel.bidding.api.AuctionSnapshot
import com.gavel.bidding.api.CancelOutcome
import com.gavel.bidding.api.OpenAuction
import com.gavel.catalog.api.AuctionSoldV1
import com.gavel.catalog.api.AuctionUnsoldV1
import com.gavel.catalog.api.CatalogEvents
import com.gavel.catalog.api.ListingCancelledV1
import com.gavel.catalog.domain.CatalogSettings
import com.gavel.catalog.domain.Listing
import com.gavel.catalog.domain.ListingContent
import com.gavel.catalog.domain.ListingPatch
import com.gavel.catalog.domain.ListingRules
import com.gavel.catalog.domain.ListingStatus
import com.gavel.kernel.Actor
import com.gavel.kernel.AuditLog
import com.gavel.kernel.DomainException
import com.gavel.kernel.ErrorKind
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.ListingId
import com.gavel.kernel.Metrics
import com.gavel.kernel.Money
import com.gavel.kernel.MoneyPolicy
import com.gavel.kernel.Page
import com.gavel.kernel.PageRequest
import com.gavel.kernel.Transactor
import com.gavel.kernel.UserId
import com.gavel.kernel.Validation
import com.gavel.kernel.conflict
import com.gavel.kernel.forbidden
import com.gavel.kernel.notFound
import com.gavel.onboarding.api.Participants
import com.gavel.onboarding.api.PublicProfile
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class Category(val slug: String, val name: String)

enum class ListingSort { ENDING_SOON, NEWEST, PRICE_ASC, PRICE_DESC }

data class ListingSearch(
    val text: String? = null,
    val category: String? = null,
    val statuses: Set<ListingStatus> = setOf(ListingStatus.ACTIVE, ListingStatus.SCHEDULED),
    val minPriceMinor: Long? = null,
    val maxPriceMinor: Long? = null,
    val endsBefore: Instant? = null,
    val sort: ListingSort = ListingSort.ENDING_SOON,
)

/** A listing with the live auction price and the seller's public profile. */
data class ListingView(val listing: Listing, val auction: AuctionSnapshot?, val seller: PublicProfile?, val category: Category?)

interface ListingRepository {
    fun insert(listing: Listing)
    fun update(listing: Listing, expectedVersion: Long): Boolean
    fun find(id: ListingId, forUpdate: Boolean = false): Listing?
    fun search(query: ListingSearch, page: PageRequest): Page<Listing>
    fun bySeller(sellerId: UserId, page: PageRequest): Page<Listing>
    /** Locks one due listing, skipping rows another instance holds. */
    fun lockNextDue(status: ListingStatus, now: Instant): Listing?
    fun applyBidProjection(listingId: ListingId, priceMinor: Long, bidCount: Int)
    fun categories(): List<Category>
}

class CatalogService(
    private val tx: Transactor,
    private val listings: ListingRepository,
    private val participants: Participants,
    private val auctions: AuctionRegistry,
    private val money: MoneyPolicy,
    private val events: EventPublisher,
    private val audit: AuditLog,
    private val metrics: Metrics,
    private val clock: Clock,
    private val settings: CatalogSettings = CatalogSettings(),
) {
    private val log = LoggerFactory.getLogger(CatalogService::class.java)
    private val json = Json

    suspend fun categories(): List<Category> = tx.inTransaction { listings.categories() }

    suspend fun search(query: ListingSearch, page: PageRequest): Page<ListingView> = tx.inTransaction {
        require(query.statuses.all { it.isPublic })
        val result = listings.search(query, page)
        Page(views(result.items), result.nextCursor)
    }

    /** Public view: drafts and cancelled listings are visible only to their seller. */
    suspend fun publicListing(id: ListingId, viewer: UserId?): ListingView = tx.inTransaction {
        val listing = listings.find(id)?.takeIf { it.status.isPublic || it.sellerId == viewer } ?: notFound("Listing")
        views(listOf(listing)).single()
    }

    suspend fun myListings(sellerId: UserId, page: PageRequest): Page<ListingView> = tx.inTransaction {
        val result = listings.bySeller(sellerId, page)
        Page(views(result.items), result.nextCursor)
    }

    suspend fun myListing(sellerId: UserId, id: ListingId): ListingView = tx.inTransaction {
        views(listOf(owned(sellerId, id))).single()
    }

    /** CAT-001: eligible sellers create drafts, which may be incomplete. */
    suspend fun createDraft(sellerId: UserId, patch: ListingPatch): ListingView {
        val content = patch.applyTo(ListingContent())
        validateShape(content)
        return tx.inTransaction {
            requireSeller(sellerId)
            val now = clock.instant()
            val listing = Listing(ListingId.new(), sellerId, ListingStatus.DRAFT, content, null, 0, null, null, null, now, now, 1)
            listings.insert(listing)
            audit.record(Actor.User(sellerId), "listing.created", "listing", listing.id.toString())
            views(listOf(listing)).single()
        }
    }

    /** CAT-005: DRAFT and SCHEDULED are editable; ACTIVE allows only non-economic fields. */
    suspend fun update(sellerId: UserId, id: ListingId, patch: ListingPatch, expectedVersion: Long?): ListingView = tx.inTransaction {
        val listing = owned(sellerId, id, forUpdate = true)
        checkVersion(listing, expectedVersion)
        when (listing.status) {
            ListingStatus.DRAFT, ListingStatus.SCHEDULED -> Unit
            ListingStatus.ACTIVE -> if (patch.touchesEconomics) {
                conflict("LISTING_LOCKED", "Only the description, condition and images can change while the auction is active.")
            }
            else -> conflict("LISTING_NOT_EDITABLE", "Listings can't be edited once the auction has ended or been cancelled.")
        }
        val content = patch.applyTo(listing.content)
        validateShape(content)
        if (listing.status == ListingStatus.SCHEDULED) requirePublishable(content)
        val next = listing.copy(content = content, updatedAt = clock.instant(), version = listing.version + 1)
        save(next, listing.version)
        audit.record(Actor.User(sellerId), "listing.updated", "listing", id.toString())
        views(listOf(next)).single()
    }

    /** CAT-006: valid drafts publish without review, to SCHEDULED or straight to ACTIVE. */
    suspend fun publish(sellerId: UserId, id: ListingId, expectedVersion: Long?): ListingView = tx.inTransaction {
        val listing = owned(sellerId, id, forUpdate = true)
        checkVersion(listing, expectedVersion)
        if (listing.status != ListingStatus.DRAFT) conflict("LISTING_NOT_DRAFT", "Only draft listings can be published.")
        requireSeller(sellerId)
        requirePublishable(listing.content)
        val now = clock.instant()
        val content = listing.content.copy(shippingPrice = listing.content.effectiveShippingPrice)
        val scheduled = listing.copy(
            status = ListingStatus.SCHEDULED, content = content, currentPrice = content.startingPrice,
            publishedAt = now, updatedAt = now, version = listing.version + 1,
        )
        val next = if (!content.startAt!!.isAfter(now)) activate(scheduled, now) else scheduled
        save(next, listing.version)
        audit.record(Actor.User(sellerId), "listing.published", "listing", id.toString(), mapOf("status" to next.status.name))
        views(listOf(next)).single()
    }

    /** CAT-009: cancellation is final and only possible before the first accepted bid. */
    suspend fun cancel(sellerId: UserId, id: ListingId, expectedVersion: Long?): ListingView = tx.inTransaction {
        val listing = owned(sellerId, id, forUpdate = true)
        checkVersion(listing, expectedVersion)
        when (listing.status) {
            ListingStatus.DRAFT, ListingStatus.SCHEDULED -> Unit
            ListingStatus.ACTIVE -> if (auctions.cancel(id) == CancelOutcome.HasBids) {
                conflict("LISTING_HAS_BIDS", "Auctions with an accepted bid can't be cancelled.")
            }
            ListingStatus.CANCELLED -> return@inTransaction views(listOf(listing)).single()
            else -> conflict("LISTING_NOT_CANCELLABLE", "This auction has already ended.")
        }
        val now = clock.instant()
        val next = listing.copy(status = ListingStatus.CANCELLED, cancelledAt = now, updatedAt = now, version = listing.version + 1)
        save(next, listing.version)
        audit.record(Actor.User(sellerId), "listing.cancelled", "listing", id.toString())
        events.publish(CatalogEvents.LISTING_CANCELLED, 1, id.toString(), json.encodeToString(ListingCancelledV1.serializer(), ListingCancelledV1(id.toString(), sellerId.toString(), now.toString())))
        views(listOf(next)).single()
    }

    // ---- Scheduled lifecycle (CAT-006, CAT-010). Safe to retry and to run on several instances. ----

    /** Activates every due SCHEDULED listing, one transaction each. Returns how many changed. */
    fun activateDue(limit: Int = 500): Int = drain(limit) {
        val now = clock.instant()
        val listing = listings.lockNextDue(ListingStatus.SCHEDULED, now) ?: return@drain false
        save(activate(listing, now).copy(version = listing.version + 1), listing.version)
        audit.record(Actor.System("scheduler"), "listing.activated", "listing", listing.id.toString())
        true
    }

    /** Closes every due ACTIVE listing as SOLD or UNSOLD, one transaction each. */
    fun closeDue(limit: Int = 500): Int = drain(limit) {
        val now = clock.instant()
        val listing = listings.lockNextDue(ListingStatus.ACTIVE, now) ?: return@drain false
        val winner = auctions.close(listing.id)
        val status = if (winner != null) ListingStatus.SOLD else ListingStatus.UNSOLD
        val next = listing.copy(
            status = status, closedAt = now, updatedAt = now, version = listing.version + 1,
            currentPrice = winner?.amount ?: listing.currentPrice,
        )
        save(next, listing.version)
        val c = listing.content
        if (winner != null) {
            val payload = AuctionSoldV1(
                listing.id.toString(), c.title!!, listing.sellerId.toString(), winner.bidderId.toString(), winner.bidId.toString(),
                winner.amount.amountMinor, c.shippingPrice!!.amountMinor, winner.amount.currency.code, now.toString(),
            )
            events.publish(CatalogEvents.AUCTION_SOLD, 1, listing.id.toString(), json.encodeToString(AuctionSoldV1.serializer(), payload))
        } else {
            val payload = AuctionUnsoldV1(listing.id.toString(), listing.sellerId.toString(), now.toString())
            events.publish(CatalogEvents.AUCTION_UNSOLD, 1, listing.id.toString(), json.encodeToString(AuctionUnsoldV1.serializer(), payload))
        }
        audit.record(Actor.System("scheduler"), "auction.closed", "listing", listing.id.toString(), mapOf("result" to status.name, "winningBidId" to winner?.bidId?.toString()))
        metrics.record("gavel.auction.close.lag", Duration.between(c.endAt, now), "result" to status.name)
        true
    }

    /** Keeps the search projection in step with accepted bids. Idempotent and order-insensitive. */
    fun onBidAccepted(listingId: ListingId, amountMinor: Long, bidCount: Int) {
        tx.blocking { listings.applyBidProjection(listingId, amountMinor, bidCount) }
    }

    private fun drain(limit: Int, step: () -> Boolean): Int {
        var changed = 0
        while (changed < limit) {
            val didWork = try {
                tx.blocking(step)
            } catch (e: Exception) {
                log.error("Listing lifecycle step failed; it will be retried on the next run", e)
                false
            }
            if (!didWork) break
            changed++
        }
        return changed
    }

    private fun activate(listing: Listing, now: Instant): Listing {
        val c = listing.content
        auctions.open(OpenAuction(listing.id, listing.sellerId, c.startingPrice!!, c.endAt!!))
        return listing.copy(status = ListingStatus.ACTIVE, updatedAt = now)
    }

    private fun views(items: List<Listing>): List<ListingView> {
        if (items.isEmpty()) return emptyList()
        val snapshots = auctions.snapshots(items.filter { it.status != ListingStatus.DRAFT }.map { it.id })
        val sellers = participants.publicProfiles(items.map { it.sellerId })
        val categories = listings.categories().associateBy { it.slug }
        return items.map { ListingView(it, snapshots[it.id], sellers[it.sellerId], it.content.categorySlug?.let(categories::get)) }
    }

    private fun owned(sellerId: UserId, id: ListingId, forUpdate: Boolean = false): Listing =
        listings.find(id, forUpdate)?.takeIf { it.sellerId == sellerId } ?: notFound("Listing")

    private fun requireSeller(sellerId: UserId) {
        val seller = participants.find(sellerId)
        when {
            seller == null || !seller.isActive -> forbidden("ACCOUNT_NOT_ELIGIBLE", "Your account can't sell right now.")
            !seller.emailVerified -> forbidden("EMAIL_NOT_VERIFIED", "Verify your email before selling.")
            !seller.sellerEligible -> forbidden("SELLER_PROFILE_INCOMPLETE", "Complete your seller profile before listing items.")
        }
    }

    private fun validateShape(content: ListingContent) {
        val v = Validation()
        ListingRules.validateShape(content, settings, v)
        checkCurrency(content.startingPrice, "startingPrice", v)
        checkCurrency(content.shippingPrice, "shippingPrice", v)
        v.throwIfInvalid()
    }

    private fun checkCurrency(value: Money?, field: String, v: Validation) {
        value?.let { v.check(it.currency == money.currency, field, "CURRENCY_MISMATCH", "Prices must be in ${money.currency}.") }
    }

    private fun requirePublishable(content: ListingContent) {
        val known = listings.categories().map { it.slug }.toSet()
        val errors = ListingRules.publicationErrors(content, clock.instant(), settings) { it in known }
        if (errors.isNotEmpty()) {
            throw DomainException(ErrorKind.RULE_VIOLATION, "LISTING_INCOMPLETE", "Fix the highlighted fields before publishing.", errors)
        }
    }

    private fun checkVersion(listing: Listing, expected: Long?) {
        if (expected != null && expected != listing.version) versionConflict()
    }

    private fun save(next: Listing, expectedVersion: Long) {
        if (!listings.update(next, expectedVersion)) versionConflict()
    }

    private fun versionConflict(): Nothing = conflict("VERSION_CONFLICT", "The listing changed since you loaded it. Reload and try again.")
}
