package com.gavel.bidding.application

import com.gavel.bidding.api.AuctionRegistry
import com.gavel.bidding.api.AuctionSnapshot
import com.gavel.bidding.api.AuctionState
import com.gavel.bidding.api.BidAcceptedV1
import com.gavel.bidding.api.BiddingEvents
import com.gavel.bidding.api.CancelOutcome
import com.gavel.bidding.api.OpenAuction
import com.gavel.bidding.api.WinningBid
import com.gavel.bidding.domain.Auction
import com.gavel.bidding.domain.BidRejection
import com.gavel.bidding.domain.BidRules
import com.gavel.kernel.Actor
import com.gavel.kernel.AuditLog
import com.gavel.kernel.BidId
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
import com.gavel.kernel.conflict
import com.gavel.onboarding.api.Participants
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant

data class AcceptedBid(
    val bidId: BidId,
    val listingId: ListingId,
    val bidderId: UserId,
    val bidderAlias: Int,
    val amount: Money,
    val sequence: Long,
    val acceptedAt: Instant,
    val idempotencyKey: String,
)

data class BidResult(val bid: AcceptedBid, val auction: AuctionSnapshot, val replayed: Boolean)

data class MyBid(val bid: AcceptedBid, val isHighest: Boolean, val auctionState: AuctionState)

interface BidRepository {
    fun insertAuction(command: OpenAuction, now: Instant): Boolean
    fun findAuction(listingId: ListingId, forUpdate: Boolean): Auction?
    fun findAuctions(listingIds: Collection<ListingId>): List<Auction>
    fun updateAfterBid(listingId: ListingId, bid: AcceptedBid, bidCount: Int)
    fun updateState(listingId: ListingId, state: AuctionState, now: Instant)
    fun insertBid(bid: AcceptedBid)
    fun findByIdempotencyKey(bidderId: UserId, key: String): AcceptedBid?
    fun aliasFor(listingId: ListingId, bidderId: UserId): Int?
    fun distinctBidderCount(listingId: ListingId): Int
    fun highestBid(listingId: ListingId): AcceptedBid?
    fun bidsForListing(listingId: ListingId, page: PageRequest): Page<AcceptedBid>
    fun bidsForBidder(bidderId: UserId, page: PageRequest): Page<AcceptedBid>
}

class BiddingService(
    private val tx: Transactor,
    private val bids: BidRepository,
    private val participants: Participants,
    private val money: MoneyPolicy,
    private val events: EventPublisher,
    private val audit: AuditLog,
    private val metrics: Metrics,
    private val clock: Clock,
) : AuctionRegistry {
    private val json = Json

    /** BID-001…BID-012. Acceptance happens in one transaction holding the auction row lock. */
    suspend fun placeBid(bidderId: UserId, listingId: ListingId, amount: Money, idempotencyKey: String): BidResult {
        val receivedAt = clock.instant()
        try {
            val result = tx.inTransaction {
                replay(bidderId, listingId, amount, idempotencyKey)?.let { return@inTransaction it }

                val bidder = participants.find(bidderId)
                if (bidder == null || !bidder.canBid) reject(BidRejection.ACCOUNT_NOT_ELIGIBLE)

                val auction = bids.findAuction(listingId, forUpdate = true) ?: reject(BidRejection.AUCTION_NOT_ACTIVE)
                // A concurrent retry with the same key may have committed while we waited for the lock.
                replay(bidderId, listingId, amount, idempotencyKey)?.let { return@inTransaction it }

                BidRules.evaluate(auction, bidderId, amount, receivedAt, money.minimumBidIncrement)?.let { reject(it) }

                val alias = bids.aliasFor(listingId, bidderId) ?: (bids.distinctBidderCount(listingId) + 1)
                val bid = AcceptedBid(BidId.new(), listingId, bidderId, alias, amount, auction.lastSequence + 1, receivedAt, idempotencyKey)
                val bidCount = auction.bidCount + 1
                bids.insertBid(bid)
                bids.updateAfterBid(listingId, bid, bidCount)

                val payload = BidAcceptedV1(listingId.toString(), bid.bidId.toString(), amount.amountMinor, amount.currency.code, bid.sequence, bidCount, receivedAt.toString())
                events.publish(BiddingEvents.BID_ACCEPTED, 1, listingId.toString(), json.encodeToString(BidAcceptedV1.serializer(), payload))
                audit.record(Actor.User(bidderId), "bid.accepted", "listing", listingId.toString(), mapOf("bidId" to bid.bidId.toString(), "amountMinor" to amount.amountMinor.toString(), "sequence" to bid.sequence.toString()))

                val snapshot = AuctionSnapshot(listingId, auction.state, amount, bidCount, BidRules.minimumNextBid(auction.startingPrice, amount, money.minimumBidIncrement))
                BidResult(bid, snapshot, replayed = false)
            }
            metrics.increment("gavel.bids", "outcome" to if (result.replayed) "replayed" else "accepted")
            return result
        } catch (e: DomainException) {
            metrics.increment("gavel.bids", "outcome" to "rejected", "reason" to e.code)
            throw e
        }
    }

    private fun replay(bidderId: UserId, listingId: ListingId, amount: Money, key: String): BidResult? {
        val existing = bids.findByIdempotencyKey(bidderId, key) ?: return null
        if (existing.listingId != listingId || existing.amount != amount) {
            conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different bid.")
        }
        val snapshot = snapshotsInTx(listOf(listingId)).getValue(listingId)
        return BidResult(existing, snapshot, replayed = true)
    }

    suspend fun publicHistory(listingId: ListingId, page: PageRequest): Page<AcceptedBid> =
        tx.inTransaction { bids.bidsForListing(listingId, page) }

    suspend fun myBids(bidderId: UserId, page: PageRequest): Page<MyBid> = tx.inTransaction {
        val result = bids.bidsForBidder(bidderId, page)
        val auctions = bids.findAuctions(result.items.map { it.listingId }.distinct()).associateBy { it.listingId }
        val highest = result.items.map { it.listingId }.distinct().associateWith { bids.highestBid(it)?.bidId }
        Page(result.items.map { MyBid(it, highest[it.listingId] == it.bidId, auctions.getValue(it.listingId).state) }, result.nextCursor)
    }

    suspend fun snapshot(listingId: ListingId): AuctionSnapshot? = tx.inTransaction { snapshotsInTx(listOf(listingId))[listingId] }

    // ---- AuctionRegistry: called by the catalog inside its own transaction ----

    override fun open(command: OpenAuction) {
        require(command.startingPrice.currency == money.currency && command.startingPrice.isPositive)
        bids.insertAuction(command, clock.instant())
    }

    override fun cancel(listingId: ListingId): CancelOutcome {
        val auction = bids.findAuction(listingId, forUpdate = true) ?: return CancelOutcome.Cancelled
        if (auction.state == AuctionState.CANCELLED) return CancelOutcome.Cancelled
        if (auction.bidCount > 0 || auction.state == AuctionState.CLOSED) return CancelOutcome.HasBids
        bids.updateState(listingId, AuctionState.CANCELLED, clock.instant())
        return CancelOutcome.Cancelled
    }

    override fun close(listingId: ListingId): WinningBid? {
        val auction = bids.findAuction(listingId, forUpdate = true) ?: return null
        if (auction.state == AuctionState.OPEN) bids.updateState(listingId, AuctionState.CLOSED, clock.instant())
        if (auction.state == AuctionState.CANCELLED) return null
        return bids.highestBid(listingId)?.let { WinningBid(it.bidId, it.bidderId, it.amount, it.sequence, it.acceptedAt) }
    }

    override fun snapshots(listingIds: Collection<ListingId>): Map<ListingId, AuctionSnapshot> = snapshotsInTx(listingIds)

    private fun snapshotsInTx(listingIds: Collection<ListingId>): Map<ListingId, AuctionSnapshot> =
        bids.findAuctions(listingIds).associate {
            it.listingId to AuctionSnapshot(it.listingId, it.state, it.highestBid, it.bidCount, BidRules.minimumNextBid(it.startingPrice, it.highestBid, money.minimumBidIncrement))
        }

    private fun reject(rejection: BidRejection): Nothing {
        val kind = when (rejection) {
            BidRejection.SELLER_CANNOT_BID, BidRejection.ACCOUNT_NOT_ELIGIBLE -> ErrorKind.FORBIDDEN
            else -> ErrorKind.RULE_VIOLATION
        }
        throw DomainException(kind, rejection.code, rejection.message)
    }
}
