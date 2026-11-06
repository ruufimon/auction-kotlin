package com.gavel.catalog.infrastructure

import com.gavel.bidding.api.AuctionRegistry
import com.gavel.bidding.api.BidAcceptedV1
import com.gavel.bidding.api.BiddingEvents
import com.gavel.catalog.application.CatalogService
import com.gavel.catalog.application.ListingSearch
import com.gavel.catalog.application.ListingSort
import com.gavel.catalog.application.ListingView
import com.gavel.catalog.domain.CatalogSettings
import com.gavel.catalog.domain.ItemCondition
import com.gavel.catalog.domain.ListingPatch
import com.gavel.catalog.domain.ListingStatus
import com.gavel.catalog.domain.Origin
import com.gavel.kernel.AuditLog
import com.gavel.kernel.EventEnvelope
import com.gavel.kernel.EventHandler
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.ListingId
import com.gavel.kernel.Metrics
import com.gavel.kernel.MoneyPolicy
import com.gavel.kernel.PageRequest
import com.gavel.kernel.Transactor
import com.gavel.onboarding.api.Participants
import com.gavel.web.MoneyDto
import com.gavel.web.PageDto
import com.gavel.web.currentUserId
import com.gavel.web.expectedVersion
import com.gavel.web.malformed
import com.gavel.web.optionalUserId
import com.gavel.web.parseInstant
import com.gavel.web.pathParam
import com.gavel.web.query
import com.gavel.web.queryInstant
import com.gavel.web.queryInt
import com.gavel.web.queryLong
import com.gavel.web.receiveJson
import com.gavel.web.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

@Serializable
data class CategoryDto(val slug: String, val name: String)

@Serializable
data class OriginDto(val city: String, val region: String, val country: String)

@Serializable
data class ShippingDto(val free: Boolean, val price: MoneyDto?)

@Serializable
data class SellerDto(val id: String, val displayName: String)

@Serializable
data class ListingDto(
    val id: String,
    val status: String,
    val title: String?,
    val description: String?,
    val category: CategoryDto?,
    val condition: String?,
    val images: List<String>,
    val startingPrice: MoneyDto?,
    /** Highest accepted bid, or the starting price before the first bid. */
    val currentPrice: MoneyDto?,
    val minimumNextBid: MoneyDto?,
    val bidCount: Int,
    val shipping: ShippingDto,
    val origin: OriginDto?,
    val startAt: String?,
    val endAt: String?,
    val seller: SellerDto?,
    val publishedAt: String?,
    val closedAt: String?,
    val version: Long,
)

@Serializable
data class ListingInput(
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val condition: String? = null,
    val images: List<String>? = null,
    val startingPrice: MoneyDto? = null,
    val shippingPrice: MoneyDto? = null,
    val freeShipping: Boolean? = null,
    val origin: OriginDto? = null,
    val startAt: String? = null,
    val endAt: String? = null,
) {
    fun toPatch() = ListingPatch(
        title = title,
        description = description,
        categorySlug = category,
        condition = condition?.let { raw ->
            runCatching { ItemCondition.valueOf(raw) }.getOrNull()
                ?: throw malformed("condition", "condition must be one of ${ItemCondition.entries.joinToString()}.")
        },
        imageUrls = images,
        startingPrice = startingPrice?.toMoney("startingPrice"),
        shippingPrice = shippingPrice?.toMoney("shippingPrice"),
        freeShipping = freeShipping,
        origin = origin?.let { Origin(it.city, it.region, it.country.uppercase()) },
        startAt = startAt?.let { parseInstant(it, "startAt") },
        endAt = endAt?.let { parseInstant(it, "endAt") },
    )
}

/** Wires the product catalog; the app mounts the routes and runs the lifecycle jobs. */
class CatalogModule(
    tx: Transactor,
    participants: Participants,
    auctions: AuctionRegistry,
    money: MoneyPolicy,
    events: EventPublisher,
    audit: AuditLog,
    metrics: Metrics,
    clock: Clock,
    settings: CatalogSettings = CatalogSettings(),
) {
    val service = CatalogService(tx, ExposedListingRepository(), participants, auctions, money, events, audit, metrics, clock, settings)

    /** Keeps the price projection used for search filters and sorting up to date. */
    val bidAcceptedHandler: EventHandler = object : EventHandler {
        override val name = "catalog.price-projection"
        override val eventType = BiddingEvents.BID_ACCEPTED
        override val payloadVersion = 1
        override fun handle(event: EventEnvelope) {
            val bid = Json.decodeFromString(BidAcceptedV1.serializer(), event.payload)
            service.onBidAccepted(ListingId.parse(bid.listingId), bid.amountMinor, bid.bidCount)
        }
    }

    /** Public browsing. Authentication is optional so sellers can preview their drafts. */
    val publicRoutes: Route.() -> Unit = {
        get("/categories") { call.respond(service.categories().map { CategoryDto(it.slug, it.name) }) }
        get("/listings") {
            val page = service.search(call.listingSearch(), PageRequest.of(call.queryInt("limit"), call.query("cursor")))
            call.respond(PageDto(page.items.map { it.toDto() }, page.nextCursor))
        }
        get("/listings/{listingId}") {
            val view = service.publicListing(ListingId.parse(call.pathParam("listingId")), call.optionalUserId())
            call.respondListing(view)
        }
    }

    /** Seller listing management; the app wraps these in bearer authentication. */
    val sellerRoutes: Route.() -> Unit = {
        route("/me/listings") {
            get {
                val page = service.myListings(call.currentUserId(), PageRequest.of(call.queryInt("limit"), call.query("cursor")))
                call.respond(PageDto(page.items.map { it.toDto() }, page.nextCursor))
            }
            post {
                val input = call.receiveJson<ListingInput>()
                call.respondListing(service.createDraft(call.currentUserId(), input.toPatch()), HttpStatusCode.Created)
            }
            route("/{listingId}") {
                get { call.respondListing(service.myListing(call.currentUserId(), call.listingId())) }
                patch {
                    val input = call.receiveJson<ListingInput>()
                    call.respondListing(service.update(call.currentUserId(), call.listingId(), input.toPatch(), call.expectedVersion()))
                }
                post("/publish") { call.respondListing(service.publish(call.currentUserId(), call.listingId(), call.expectedVersion())) }
                post("/cancel") { call.respondListing(service.cancel(call.currentUserId(), call.listingId(), call.expectedVersion())) }
            }
        }
    }

    private fun ApplicationCall.listingId() = ListingId.parse(pathParam("listingId"))

    private suspend fun ApplicationCall.respondListing(view: ListingView, status: HttpStatusCode = HttpStatusCode.OK) {
        response.header("ETag", "\"${view.listing.version}\"")
        respond(status, view.toDto())
    }

    private fun ApplicationCall.listingSearch(): ListingSearch {
        val statuses = query("status")?.split(',')?.map { raw ->
            ListingStatus.entries.firstOrNull { it.name == raw.trim().uppercase() && it.isPublic }
                ?: throw malformed("status", "status accepts SCHEDULED, ACTIVE, SOLD and UNSOLD.")
        }?.toSet()
        val sort = query("sort")?.let { raw ->
            ListingSort.entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw malformed("sort", "sort accepts ending_soon, newest, price_asc and price_desc.")
        }
        val text = query("q")?.trim()
        if (text != null && text.length > 100) throw malformed("q", "Search text must be at most 100 characters.")
        return ListingSearch(
            text = text,
            category = query("category"),
            statuses = statuses ?: ListingSearch().statuses,
            minPriceMinor = queryLong("minPrice"),
            maxPriceMinor = queryLong("maxPrice"),
            endsBefore = queryInstant("endsBefore"),
            sort = sort ?: ListingSort.ENDING_SOON,
        )
    }
}

internal fun ListingView.toDto(): ListingDto {
    val l = listing
    val c = l.content
    return ListingDto(
        id = l.id.toString(),
        status = l.status.name,
        title = c.title,
        description = c.description,
        category = category?.let { CategoryDto(it.slug, it.name) },
        condition = c.condition?.name,
        images = c.imageUrls,
        startingPrice = c.startingPrice?.toDto(),
        currentPrice = (auction?.highestBid ?: l.currentPrice ?: c.startingPrice)?.toDto(),
        minimumNextBid = (auction?.minimumNextBid ?: c.startingPrice)?.takeIf { l.status == ListingStatus.ACTIVE || l.status == ListingStatus.SCHEDULED }?.toDto(),
        bidCount = auction?.bidCount ?: l.bidCount,
        shipping = ShippingDto(c.freeShipping, c.effectiveShippingPrice?.toDto()),
        origin = c.origin?.let { OriginDto(it.city, it.region, it.country) },
        startAt = c.startAt?.toString(),
        endAt = c.endAt?.toString(),
        seller = seller?.let { SellerDto(it.id.toString(), it.displayName) },
        publishedAt = l.publishedAt?.toString(),
        closedAt = l.closedAt?.toString(),
        version = l.version,
    )
}
