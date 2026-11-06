package com.gavel.bidding.infrastructure

import com.gavel.bidding.api.AuctionRegistry
import com.gavel.bidding.api.AuctionSnapshot
import com.gavel.bidding.application.AcceptedBid
import com.gavel.bidding.application.BidResult
import com.gavel.bidding.application.BiddingService
import com.gavel.kernel.AuditLog
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
import com.gavel.web.idempotencyKey
import com.gavel.web.pathParam
import com.gavel.web.query
import com.gavel.web.queryInt
import com.gavel.web.receiveJson
import com.gavel.web.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class PlaceBidRequest(val amount: MoneyDto)

@Serializable
data class AuctionPriceDto(
    val currentPrice: MoneyDto?,
    val minimumNextBid: MoneyDto,
    val bidCount: Int,
    val state: String,
)

@Serializable
data class BidResultDto(
    val bidId: String,
    val listingId: String,
    val amount: MoneyDto,
    val sequence: Long,
    val acceptedAt: String,
    val auction: AuctionPriceDto,
)

@Serializable
data class PublicBidDto(val sequence: Long, val bidder: String, val amount: MoneyDto, val acceptedAt: String)

@Serializable
data class MyBidDto(
    val bidId: String,
    val listingId: String,
    val amount: MoneyDto,
    val sequence: Long,
    val acceptedAt: String,
    val isHighest: Boolean,
    val auctionState: String,
)

fun AuctionSnapshot.toDto() = AuctionPriceDto(highestBid?.toDto(), minimumNextBid.toDto(), bidCount, state.name)

class BiddingModule(
    tx: Transactor,
    participants: Participants,
    money: MoneyPolicy,
    events: EventPublisher,
    audit: AuditLog,
    metrics: Metrics,
    clock: Clock,
) {
    val service = BiddingService(tx, ExposedBidRepository(), participants, money, events, audit, metrics, clock)
    val registry: AuctionRegistry get() = service

    val publicRoutes: Route.() -> Unit = {
        get("/listings/{listingId}/bids") {
            val page = service.publicHistory(ListingId.parse(call.pathParam("listingId")), PageRequest.of(call.queryInt("limit"), call.query("cursor")))
            call.respond(PageDto(page.items.map { it.toPublicDto() }, page.nextCursor))
        }
    }

    val userRoutes: Route.() -> Unit = {
        post("/listings/{listingId}/bids") {
            val key = call.idempotencyKey()
            val body = call.receiveJson<PlaceBidRequest>()
            val result = service.placeBid(call.currentUserId(), ListingId.parse(call.pathParam("listingId")), body.amount.toMoney("amount"), key)
            if (result.replayed) call.response.header("Idempotent-Replayed", "true")
            call.respond(if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created, result.toDto())
        }
        get("/me/bids") {
            val page = service.myBids(call.currentUserId(), PageRequest.of(call.queryInt("limit"), call.query("cursor")))
            call.respond(
                PageDto(
                    page.items.map { MyBidDto(it.bid.bidId.toString(), it.bid.listingId.toString(), it.bid.amount.toDto(), it.bid.sequence, it.bid.acceptedAt.toString(), it.isHighest, it.auctionState.name) },
                    page.nextCursor,
                ),
            )
        }
    }

    private fun BidResult.toDto() = BidResultDto(bid.bidId.toString(), bid.listingId.toString(), bid.amount.toDto(), bid.sequence, bid.acceptedAt.toString(), auction.toDto())

    private fun AcceptedBid.toPublicDto() = PublicBidDto(sequence, "Bidder $bidderAlias", amount.toDto(), acceptedAt.toString())
}
