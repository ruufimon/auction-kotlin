package com.gavel.settlement.infrastructure

import com.gavel.catalog.api.AuctionSoldV1
import com.gavel.catalog.api.CatalogEvents
import com.gavel.kernel.AuditLog
import com.gavel.kernel.EventEnvelope
import com.gavel.kernel.EventHandler
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.Metrics
import com.gavel.kernel.OrderId
import com.gavel.kernel.PageRequest
import com.gavel.kernel.Transactor
import com.gavel.kernel.UserId
import com.gavel.onboarding.api.Participants
import com.gavel.settlement.application.OrderRole
import com.gavel.settlement.application.PaymentProvider
import com.gavel.settlement.application.SettlementService
import com.gavel.settlement.application.SettlementSettings
import com.gavel.settlement.domain.Order
import com.gavel.settlement.domain.PaymentAttempt
import com.gavel.web.MoneyDto
import com.gavel.web.PageDto
import com.gavel.web.currentUserId
import com.gavel.web.expectedVersion
import com.gavel.web.malformed
import com.gavel.web.pathParam
import com.gavel.web.query
import com.gavel.web.queryInt
import com.gavel.web.receiveJson
import com.gavel.web.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

@Serializable
data class ShipToDto(
    val name: String,
    val phone: String?,
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String,
    val postalCode: String,
    val country: String,
)

@Serializable
data class ShipmentDto(val carrier: String, val trackingReference: String, val shippedAt: String)

@Serializable
data class OrderDto(
    val id: String,
    val listingId: String,
    val title: String,
    val status: String,
    /** The caller's role on this order: BUYER or SELLER. */
    val role: String,
    val buyerId: String,
    val sellerId: String,
    val itemPrice: MoneyDto,
    val shippingPrice: MoneyDto,
    val total: MoneyDto,
    val paymentDeadline: String,
    /** Shown to the buyer always, and to the seller once the order is paid. */
    val shipTo: ShipToDto?,
    val paidAt: String?,
    val shipment: ShipmentDto?,
    val deliveredAt: String?,
    val createdAt: String,
    val version: Long,
)

@Serializable
data class CheckoutDto(val orderId: String, val attemptNumber: Int, val checkoutUrl: String, val status: String, val resumed: Boolean)

@Serializable
data class ShipRequest(val carrier: String, val trackingReference: String)

@Serializable
data class WebhookAck(val received: Boolean = true)

/** Wires settlement; the app mounts its routes and runs the expiry job. */
class SettlementModule(
    tx: Transactor,
    participants: Participants,
    provider: PaymentProvider,
    events: EventPublisher,
    audit: AuditLog,
    metrics: Metrics,
    clock: Clock,
    settings: SettlementSettings = SettlementSettings(),
) {
    val service = SettlementService(tx, ExposedOrderRepository(clock), participants, provider, events, audit, metrics, clock, settings)

    val auctionSoldHandler: EventHandler = object : EventHandler {
        override val name = "settlement.create-order"
        override val eventType = CatalogEvents.AUCTION_SOLD
        override val payloadVersion = 1
        override fun handle(event: EventEnvelope) = service.onAuctionSold(Json.decodeFromString(AuctionSoldV1.serializer(), event.payload))
    }

    /** Signed provider callbacks; unauthenticated but signature-verified. */
    val webhookRoutes: Route.() -> Unit = {
        post("/payments/webhooks/{provider}") {
            if (call.pathParam("provider") != provider.name) throw com.gavel.kernel.DomainException(com.gavel.kernel.ErrorKind.NOT_FOUND, "NOT_FOUND", "Unknown payment provider.")
            val body = call.receiveText()
            service.handleWebhook({ call.request.headers[it] }, body)
            call.respond(WebhookAck())
        }
    }

    val userRoutes: Route.() -> Unit = {
        get("/me/orders") {
            val role = when (call.query("role")?.lowercase() ?: "buyer") {
                "buyer" -> OrderRole.BUYER
                "seller" -> OrderRole.SELLER
                else -> throw malformed("role", "role must be buyer or seller.")
            }
            val userId = call.currentUserId()
            val page = service.orders(userId, role, PageRequest.of(call.queryInt("limit"), call.query("cursor")))
            call.respond(PageDto(page.items.map { it.toDto(userId) }, page.nextCursor))
        }
        route("/orders/{orderId}") {
            get {
                val userId = call.currentUserId()
                call.respond(service.order(userId, call.orderId()).toDto(userId))
            }
            post("/checkout") {
                val result = service.checkout(call.currentUserId(), call.orderId())
                call.respond(if (result.resumed) HttpStatusCode.OK else HttpStatusCode.Created, result.attempt.toCheckoutDto(result.resumed))
            }
            post("/shipment") {
                val userId = call.currentUserId()
                val body = call.receiveJson<ShipRequest>()
                call.respond(service.ship(userId, call.orderId(), body.carrier, body.trackingReference, call.expectedVersion()).toDto(userId))
            }
            post("/delivery") {
                val userId = call.currentUserId()
                call.respond(service.confirmDelivery(userId, call.orderId(), call.expectedVersion()).toDto(userId))
            }
        }
    }

    private fun ApplicationCall.orderId() = OrderId.parse(pathParam("orderId"))

    private fun PaymentAttempt.toCheckoutDto(resumed: Boolean) = CheckoutDto(orderId.toString(), attemptNumber, checkoutUrl, status.name, resumed)

    private fun Order.toDto(viewer: UserId): OrderDto {
        val isBuyer = viewer == buyerId
        val showAddress = isBuyer || paidAt != null
        return OrderDto(
            id = id.toString(), listingId = listingId.toString(), title = title, status = status.name,
            role = if (isBuyer) "BUYER" else "SELLER", buyerId = buyerId.toString(), sellerId = sellerId.toString(),
            itemPrice = itemPrice.toDto(), shippingPrice = shippingPrice.toDto(), total = total.toDto(),
            paymentDeadline = paymentDeadline.toString(),
            shipTo = shipTo?.takeIf { showAddress }?.let { ShipToDto(it.name, it.phone, it.line1, it.line2, it.city, it.region, it.postalCode, it.country) },
            paidAt = paidAt?.toString(),
            shipment = shipment?.let { ShipmentDto(it.carrier, it.trackingReference, it.shippedAt.toString()) },
            deliveredAt = deliveredAt?.toString(), createdAt = createdAt.toString(), version = version,
        )
    }
}
