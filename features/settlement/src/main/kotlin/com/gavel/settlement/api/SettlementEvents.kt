package com.gavel.settlement.api

import kotlinx.serialization.Serializable

object SettlementEvents {
    /** Payload v1: [OrderPaidV1]. The order is now eligible for fulfillment. */
    const val ORDER_PAID = "settlement.order-paid"
}

@Serializable
data class OrderPaidV1(val orderId: String, val listingId: String, val buyerId: String, val sellerId: String, val totalMinor: Long, val currency: String, val paidAt: String)
