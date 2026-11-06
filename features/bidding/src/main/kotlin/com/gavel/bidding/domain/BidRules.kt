package com.gavel.bidding.domain

import com.gavel.bidding.api.AuctionState
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.UserId
import java.time.Instant

data class Auction(
    val listingId: ListingId,
    val sellerId: UserId,
    val startingPrice: Money,
    val endAt: Instant,
    val state: AuctionState,
    val highestBid: Money?,
    val bidCount: Int,
    val lastSequence: Long,
)

enum class BidRejection(val code: String, val message: String) {
    AUCTION_NOT_ACTIVE("AUCTION_NOT_ACTIVE", "This auction is not accepting bids."),
    AUCTION_ENDED("AUCTION_ENDED", "This auction has ended."),
    SELLER_CANNOT_BID("SELLER_CANNOT_BID", "Sellers cannot bid on their own listings."),
    BID_TOO_LOW("BID_TOO_LOW", "The bid is below the minimum next bid."),
    CURRENCY_MISMATCH("CURRENCY_MISMATCH", "Bids must use the auction currency."),
    ACCOUNT_NOT_ELIGIBLE("ACCOUNT_NOT_ELIGIBLE", "Verify your email and keep your account active to bid."),
}

object BidRules {
    /** BID-003/BID-004. */
    fun minimumNextBid(startingPrice: Money, highestBid: Money?, increment: Money): Money =
        highestBid?.plus(increment) ?: startingPrice

    /**
     * Evaluates a bid against the latest committed auction state. The deadline
     * rule is receivedAt < endAt, independent of whether closing has run.
     */
    fun evaluate(auction: Auction, bidderId: UserId, amount: Money, receivedAt: Instant, increment: Money): BidRejection? = when {
        auction.state == AuctionState.CANCELLED -> BidRejection.AUCTION_NOT_ACTIVE
        auction.state == AuctionState.CLOSED || !receivedAt.isBefore(auction.endAt) -> BidRejection.AUCTION_ENDED
        auction.sellerId == bidderId -> BidRejection.SELLER_CANNOT_BID
        amount.currency != auction.startingPrice.currency -> BidRejection.CURRENCY_MISMATCH
        amount < minimumNextBid(auction.startingPrice, auction.highestBid, increment) -> BidRejection.BID_TOO_LOW
        else -> null
    }
}
