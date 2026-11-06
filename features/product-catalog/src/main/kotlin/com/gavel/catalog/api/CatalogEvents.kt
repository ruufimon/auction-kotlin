package com.gavel.catalog.api

import kotlinx.serialization.Serializable

/** Integration events published by the product catalog. */
object CatalogEvents {
    /** Payload v1: [AuctionSoldV1]. Settlement creates the winning order from it. */
    const val AUCTION_SOLD = "catalog.auction-sold"

    /** Payload v1: [AuctionUnsoldV1]. */
    const val AUCTION_UNSOLD = "catalog.auction-unsold"

    /** Payload v1: [ListingCancelledV1]. */
    const val LISTING_CANCELLED = "catalog.listing-cancelled"
}

@Serializable
data class AuctionSoldV1(
    val listingId: String,
    val title: String,
    val sellerId: String,
    val buyerId: String,
    val winningBidId: String,
    val winningAmountMinor: Long,
    val shippingPriceMinor: Long,
    val currency: String,
    val closedAt: String,
)

@Serializable
data class AuctionUnsoldV1(val listingId: String, val sellerId: String, val closedAt: String)

@Serializable
data class ListingCancelledV1(val listingId: String, val sellerId: String, val cancelledAt: String)
