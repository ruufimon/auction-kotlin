package com.gavel.bidding.domain

import com.gavel.bidding.api.AuctionState
import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.UserId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BidRulesTest {
    private val usd = CurrencyCode("USD")
    private val increment = Money(100, usd)
    private val seller = UserId.new()
    private val bidder = UserId.new()
    private val end = Instant.parse("2026-11-03T12:00:00Z")
    private val auction = Auction(ListingId.new(), seller, Money(1_000, usd), end, AuctionState.OPEN, null, 0, 0)

    private fun evaluate(amount: Long, at: Instant = end.minusSeconds(1), a: Auction = auction, who: UserId = bidder) =
        BidRules.evaluate(a, who, Money(amount, usd), at, increment)

    @Test
    fun `first bid needs the starting price, later bids the increment`() {
        assertEquals(BidRejection.BID_TOO_LOW, evaluate(999))
        assertNull(evaluate(1_000))
        val withBid = auction.copy(highestBid = Money(1_000, usd), bidCount = 1, lastSequence = 1)
        assertEquals(BidRejection.BID_TOO_LOW, evaluate(1_099, a = withBid))
        assertNull(evaluate(1_100, a = withBid))
    }

    @Test
    fun `deadline is exclusive and state wins over amount`() {
        assertEquals(BidRejection.AUCTION_ENDED, evaluate(5_000, at = end))
        assertEquals(BidRejection.AUCTION_ENDED, evaluate(5_000, a = auction.copy(state = AuctionState.CLOSED)))
        assertEquals(BidRejection.AUCTION_NOT_ACTIVE, evaluate(5_000, a = auction.copy(state = AuctionState.CANCELLED)))
        assertEquals(BidRejection.SELLER_CANNOT_BID, evaluate(5_000, who = seller))
        assertEquals(BidRejection.CURRENCY_MISMATCH, BidRules.evaluate(auction, bidder, Money(5_000, CurrencyCode("EUR")), end.minusSeconds(1), increment))
    }
}
