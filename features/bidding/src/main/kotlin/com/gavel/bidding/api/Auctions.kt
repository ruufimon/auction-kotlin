package com.gavel.bidding.api

import com.gavel.kernel.BidId
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.UserId
import java.time.Instant

data class OpenAuction(val listingId: ListingId, val sellerId: UserId, val startingPrice: Money, val endAt: Instant)

enum class AuctionState { OPEN, CLOSED, CANCELLED }

data class AuctionSnapshot(
    val listingId: ListingId,
    val state: AuctionState,
    val highestBid: Money?,
    val bidCount: Int,
    val minimumNextBid: Money,
)

data class WinningBid(val bidId: BidId, val bidderId: UserId, val amount: Money, val sequence: Long, val acceptedAt: Instant)

sealed interface CancelOutcome {
    data object Cancelled : CancelOutcome
    data object HasBids : CancelOutcome
}

/**
 * Commands the catalog issues as listings move through their lifecycle. All
 * calls join the caller's transaction so listing and auction state commit together.
 */
interface AuctionRegistry {
    /** Opens bidding when a listing becomes ACTIVE. Idempotent. */
    fun open(command: OpenAuction)

    /** CAT-009: succeeds only before the first accepted bid. */
    fun cancel(listingId: ListingId): CancelOutcome

    /** BID-009: closes bidding and returns the final highest bid, if any. Idempotent. */
    fun close(listingId: ListingId): WinningBid?

    fun snapshots(listingIds: Collection<ListingId>): Map<ListingId, AuctionSnapshot>
}

object BiddingEvents {
    /** Payload v1: [BidAcceptedV1]. */
    const val BID_ACCEPTED = "bidding.bid-accepted"
}

@kotlinx.serialization.Serializable
data class BidAcceptedV1(
    val listingId: String,
    val bidId: String,
    val amountMinor: Long,
    val currency: String,
    val sequence: Long,
    val bidCount: Int,
    val acceptedAt: String,
)
