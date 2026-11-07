package com.gavel.bidding.api

import kotlinx.serialization.Serializable

/** Integration events published by the bidding feature. */
object BiddingEvents {
    /** Payload v1: [BidPlacedV1]. Bid has been accepted and recorded. */
    const val BID_PLACED = "bidding.bid-placed"

    /** Payload v1: [BidRejectedV1]. Bid was rejected for a specific reason. */
    const val BID_REJECTED = "bidding.bid-rejected"

    /** Payload v1: [HighBidUpdatedV1]. A new bid became the highest bid. */
    const val HIGH_BID_UPDATED = "bidding.high-bid-updated"
}

@Serializable
data class BidPlacedV1(
    val auctionId: String,
    val bidderId: String,
    val bidId: String,
    val amountMinor: Long,
    val currency: String,
    val sequenceNumber: Long,
    val placedAt: String,
)

@Serializable
data class BidRejectedV1(
    val auctionId: String,
    val bidderId: String,
    val amountMinor: Long,
    val currency: String,
    val reason: String, // AUCTION_NOT_ACTIVE, AUCTION_ENDED, SELLER_CANNOT_BID, BID_TOO_LOW, ACCOUNT_NOT_ELIGIBLE
    val rejectedAt: String,
)

@Serializable
data class HighBidUpdatedV1(
    val auctionId: String,
    val previousHighBidderId: String?,
    val previousHighAmountMinor: Long,
    val newHighBidderId: String,
    val newHighAmountMinor: Long,
    val currency: String,
    val updatedAt: String,
)
