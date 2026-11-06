package com.gavel.settlement.domain

import com.gavel.kernel.BidId
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.OrderId
import com.gavel.kernel.UserId
import com.gavel.onboarding.api.ShippingContact
import java.time.Instant
import java.util.UUID

enum class OrderStatus {
    PAYMENT_PENDING, PAID, SHIPPED, DELIVERED, PAYMENT_EXPIRED;

    val isTerminal get() = this == DELIVERED || this == PAYMENT_EXPIRED
}

enum class AttemptStatus { PENDING, SUCCEEDED, FAILED }

enum class DeliverySource { BUYER, CARRIER }

data class Shipment(val carrier: String, val trackingReference: String, val shippedAt: Instant)

data class Order(
    val id: OrderId,
    val listingId: ListingId,
    val title: String,
    val winningBidId: BidId,
    val buyerId: UserId,
    val sellerId: UserId,
    val itemPrice: Money,
    val shippingPrice: Money,
    val status: OrderStatus,
    val paymentDeadline: Instant,
    val shipTo: ShippingContact?,
    val paidAt: Instant?,
    val shipment: Shipment?,
    val deliveredAt: Instant?,
    val deliverySource: DeliverySource?,
    val createdAt: Instant,
    val version: Long,
) {
    /** SET-002: winning bid plus the listing's shipping price. */
    val total: Money get() = itemPrice + shippingPrice

    fun canCheckout(now: Instant) = status == OrderStatus.PAYMENT_PENDING && now.isBefore(paymentDeadline)

    fun markPaid(now: Instant): Order? = if (status == OrderStatus.PAYMENT_PENDING) copy(status = OrderStatus.PAID, paidAt = now) else null

    fun ship(shipment: Shipment): Order? = if (status == OrderStatus.PAID) copy(status = OrderStatus.SHIPPED, shipment = shipment) else null

    fun deliver(now: Instant, source: DeliverySource): Order? =
        if (status == OrderStatus.SHIPPED) copy(status = OrderStatus.DELIVERED, deliveredAt = now, deliverySource = source) else null

    /** SET-007: unpaid orders expire at their deadline. */
    fun expire(now: Instant): Order? =
        if (status == OrderStatus.PAYMENT_PENDING && !now.isBefore(paymentDeadline)) copy(status = OrderStatus.PAYMENT_EXPIRED) else null
}

data class PaymentAttempt(
    val id: UUID,
    val orderId: OrderId,
    val attemptNumber: Int,
    val provider: String,
    val providerReference: String,
    val checkoutUrl: String,
    val amount: Money,
    val status: AttemptStatus,
    val createdAt: Instant,
)
