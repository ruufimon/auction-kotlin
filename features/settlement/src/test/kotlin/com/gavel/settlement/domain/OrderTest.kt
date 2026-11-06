package com.gavel.settlement.domain

import com.gavel.kernel.BidId
import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.OrderId
import com.gavel.kernel.UserId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderTest {
    private val usd = CurrencyCode("USD")
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val order = Order(
        OrderId.new(), ListingId.new(), "Leica", BidId.new(), UserId.new(), UserId.new(), Money(20_000, usd), Money(1_500, usd),
        OrderStatus.PAYMENT_PENDING, now + Duration.ofHours(24), null, null, null, null, null, now, 1,
    )

    @Test
    fun `total is the winning bid plus shipping`() = assertEquals(Money(21_500, usd), order.total)

    @Test
    fun `orders follow PAYMENT_PENDING to PAID to SHIPPED to DELIVERED`() {
        val paid = order.markPaid(now)!!
        assertNull(order.ship(Shipment("UPS", "1Z", now)), "unpaid orders cannot ship")
        val shipped = paid.ship(Shipment("UPS", "1Z", now))!!
        assertNull(paid.deliver(now, DeliverySource.BUYER))
        assertEquals(OrderStatus.DELIVERED, shipped.deliver(now, DeliverySource.BUYER)!!.status)
        assertNull(paid.markPaid(now), "paying twice changes nothing")
    }

    @Test
    fun `unpaid orders expire at the deadline, not before`() {
        assertNull(order.expire(now + Duration.ofHours(23)))
        assertEquals(OrderStatus.PAYMENT_EXPIRED, order.expire(now + Duration.ofHours(24))!!.status)
        assertNull(order.markPaid(now)!!.expire(now + Duration.ofDays(2)))
        assertEquals(false, order.canCheckout(now + Duration.ofHours(24)))
    }
}
