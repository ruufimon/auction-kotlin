package com.gavel.settlement.application

import com.gavel.catalog.api.AuctionSoldV1
import com.gavel.kernel.Actor
import com.gavel.kernel.AuditLog
import com.gavel.kernel.BidId
import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.ListingId
import com.gavel.kernel.Metrics
import com.gavel.kernel.Money
import com.gavel.kernel.OrderId
import com.gavel.kernel.Page
import com.gavel.kernel.PageRequest
import com.gavel.kernel.Transactor
import com.gavel.kernel.UserId
import com.gavel.kernel.Validation
import com.gavel.kernel.conflict
import com.gavel.kernel.forbidden
import com.gavel.kernel.notFound
import com.gavel.kernel.ruleViolation
import com.gavel.onboarding.api.Participants
import com.gavel.settlement.api.OrderPaidV1
import com.gavel.settlement.api.SettlementEvents
import com.gavel.settlement.domain.AttemptStatus
import com.gavel.settlement.domain.DeliverySource
import com.gavel.settlement.domain.Order
import com.gavel.settlement.domain.OrderStatus
import com.gavel.settlement.domain.PaymentAttempt
import com.gavel.settlement.domain.Shipment
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ---- Payment provider port (SET-004/005/006) ----

data class CheckoutRequest(val orderId: OrderId, val amount: Money, val description: String, val idempotencyKey: String)

data class CheckoutSession(val providerReference: String, val checkoutUrl: String)

enum class ProviderOutcome { SUCCEEDED, FAILED, IGNORED }

data class ProviderEvent(val eventId: String, val outcome: ProviderOutcome, val providerReference: String?, val amount: Money?)

/** Provider-neutral payment integration. Implementations make network calls, so never call them inside a transaction. */
interface PaymentProvider {
    val name: String
    fun createCheckout(request: CheckoutRequest): CheckoutSession

    /** Verifies the signature and parses the event; throws [DomainException] when the signature is invalid. */
    fun parseWebhook(headers: (String) -> String?, body: String): ProviderEvent
}

interface OrderRepository {
    /** Inserts unless an order already exists for the listing. Returns true when inserted. */
    fun insertIfAbsent(order: Order): Boolean
    fun find(id: OrderId, forUpdate: Boolean = false): Order?
    fun update(order: Order, expectedVersion: Long): Boolean
    fun forParticipant(userId: UserId, role: OrderRole, page: PageRequest): Page<Order>
    fun lockNextExpired(now: Instant): Order?

    fun attempts(orderId: OrderId): List<PaymentAttempt>
    fun insertAttemptIfAbsent(attempt: PaymentAttempt): Boolean
    fun findAttemptByReference(provider: String, reference: String, forUpdate: Boolean): PaymentAttempt?
    fun updateAttemptStatus(id: UUID, status: AttemptStatus, now: Instant)
    /** Records a provider event ID; false means it was already processed. */
    fun recordProviderEvent(provider: String, eventId: String, now: Instant): Boolean
}

enum class OrderRole { BUYER, SELLER }

data class SettlementSettings(val paymentWindow: Duration = Duration.ofHours(24))

data class CheckoutResult(val order: Order, val attempt: PaymentAttempt, val resumed: Boolean)

class SettlementService(
    private val tx: Transactor,
    private val orders: OrderRepository,
    private val participants: Participants,
    private val provider: PaymentProvider,
    private val events: EventPublisher,
    private val audit: AuditLog,
    private val metrics: Metrics,
    private val clock: Clock,
    private val settings: SettlementSettings = SettlementSettings(),
) {
    private val log = LoggerFactory.getLogger(SettlementService::class.java)
    private val json = Json

    /** SET-001/002/003: exactly one order per sold auction, even when the event is redelivered. */
    fun onAuctionSold(event: AuctionSoldV1) {
        tx.blocking {
            val currency = CurrencyCode(event.currency)
            val closedAt = Instant.parse(event.closedAt)
            val buyer = UserId.parse(event.buyerId)
            val order = Order(
                id = OrderId.new(), listingId = ListingId.parse(event.listingId), title = event.title,
                winningBidId = BidId(UUID.fromString(event.winningBidId)), buyerId = buyer, sellerId = UserId.parse(event.sellerId),
                itemPrice = Money(event.winningAmountMinor, currency), shippingPrice = Money(event.shippingPriceMinor, currency),
                status = OrderStatus.PAYMENT_PENDING, paymentDeadline = closedAt + settings.paymentWindow,
                shipTo = participants.shippingContact(buyer), paidAt = null, shipment = null, deliveredAt = null, deliverySource = null,
                createdAt = clock.instant(), version = 1,
            )
            if (orders.insertIfAbsent(order)) {
                audit.record(Actor.System("settlement"), "order.created", "order", order.id.toString(), mapOf("listingId" to event.listingId, "totalMinor" to order.total.amountMinor.toString()))
            }
        }
    }

    suspend fun order(viewer: UserId, id: OrderId): Order = tx.inTransaction { visible(viewer, id) }

    suspend fun attempts(viewer: UserId, id: OrderId): List<PaymentAttempt> = tx.inTransaction {
        val order = visible(viewer, id)
        if (order.buyerId != viewer) emptyList() else orders.attempts(id)
    }

    suspend fun orders(userId: UserId, role: OrderRole, page: PageRequest): Page<Order> =
        tx.inTransaction { orders.forParticipant(userId, role, page) }

    /** SET-004/010: only the winning buyer, only while payment is pending. Resumes an open attempt. */
    suspend fun checkout(buyerId: UserId, id: OrderId): CheckoutResult {
        val now = clock.instant()
        val (order, open, attemptNumber) = tx.inTransaction {
            val order = orders.find(id, forUpdate = true)?.takeIf { it.buyerId == buyerId } ?: notFound("Order")
            if (!order.canCheckout(now)) conflict("ORDER_NOT_PAYABLE", "This order can no longer be paid.")
            val buyer = participants.find(buyerId)
            if (buyer == null || !buyer.isActive) forbidden("ACCOUNT_NOT_ELIGIBLE", "Your account can't pay for orders right now.")
            // The buyer confirms the current shipping address by checking out.
            val shipTo = participants.shippingContact(buyerId) ?: ruleViolation("SHIPPING_ADDRESS_REQUIRED", "Add a shipping address before checking out.")
            val current = if (shipTo != order.shipTo) order.copy(shipTo = shipTo, version = order.version + 1).also { save(it, order.version) } else order
            val attempts = orders.attempts(id)
            Triple(current, attempts.firstOrNull { it.status == AttemptStatus.PENDING }, (attempts.maxOfOrNull { it.attemptNumber } ?: 0) + 1)
        }
        if (open != null) return CheckoutResult(order, open, resumed = true)

        // External call outside any transaction; the idempotency key makes concurrent calls converge.
        val session = provider.createCheckout(CheckoutRequest(id, order.total, order.title, "order:$id:attempt:$attemptNumber"))

        return tx.inTransaction {
            val attempt = PaymentAttempt(UUID.randomUUID(), id, attemptNumber, provider.name, session.providerReference, session.checkoutUrl, order.total, AttemptStatus.PENDING, now)
            val inserted = orders.insertAttemptIfAbsent(attempt)
            if (inserted) audit.record(Actor.User(buyerId), "payment.checkout-started", "order", id.toString(), mapOf("attempt" to attemptNumber.toString()))
            CheckoutResult(order, orders.attempts(id).first { it.attemptNumber == attemptNumber }, resumed = !inserted)
        }
    }

    /** SET-005/006: the verified webhook is authoritative; duplicates have no further effect. */
    suspend fun handleWebhook(headers: (String) -> String?, body: String) {
        val event = provider.parseWebhook(headers, body)
        val now = clock.instant()
        tx.inTransaction {
            if (!orders.recordProviderEvent(provider.name, event.eventId, now)) return@inTransaction
            if (event.outcome == ProviderOutcome.IGNORED || event.providerReference == null) return@inTransaction
            val attempt = orders.findAttemptByReference(provider.name, event.providerReference, forUpdate = true)
            if (attempt == null) {
                log.warn("Payment webhook for an unknown provider reference was ignored")
                return@inTransaction
            }
            val actor = Actor.Provider(provider.name)
            when (event.outcome) {
                ProviderOutcome.FAILED -> if (attempt.status == AttemptStatus.PENDING) {
                    orders.updateAttemptStatus(attempt.id, AttemptStatus.FAILED, now)
                    audit.record(actor, "payment.failed", "order", attempt.orderId.toString(), mapOf("attempt" to attempt.attemptNumber.toString()))
                    metrics.increment("gavel.payments", "outcome" to "failed")
                }
                ProviderOutcome.SUCCEEDED -> {
                    if (attempt.status != AttemptStatus.SUCCEEDED) orders.updateAttemptStatus(attempt.id, AttemptStatus.SUCCEEDED, now)
                    val order = orders.find(attempt.orderId, forUpdate = true) ?: return@inTransaction
                    if (event.amount != null && event.amount != order.total) {
                        audit.record(actor, "payment.amount-mismatch", "order", order.id.toString(), mapOf("reportedMinor" to event.amount.amountMinor.toString()))
                    }
                    val paid = order.markPaid(now)
                    if (paid == null) {
                        // e.g. paid after expiry: keep the record for operator-assisted resolution (SET-012).
                        audit.record(actor, "payment.succeeded-unapplied", "order", order.id.toString(), mapOf("orderStatus" to order.status.name))
                        return@inTransaction
                    }
                    save(paid.copy(version = order.version + 1), order.version)
                    audit.record(actor, "order.paid", "order", order.id.toString(), mapOf("attempt" to attempt.attemptNumber.toString()))
                    val payload = OrderPaidV1(order.id.toString(), order.listingId.toString(), order.buyerId.toString(), order.sellerId.toString(), order.total.amountMinor, order.total.currency.code, now.toString())
                    events.publish(SettlementEvents.ORDER_PAID, 1, order.id.toString(), json.encodeToString(OrderPaidV1.serializer(), payload))
                    metrics.increment("gavel.payments", "outcome" to "succeeded")
                }
                ProviderOutcome.IGNORED -> Unit
            }
        }
    }

    /** SET-008/010: only the order's seller, after payment. */
    suspend fun ship(sellerId: UserId, id: OrderId, carrier: String, trackingReference: String, expectedVersion: Long?): Order {
        val v = Validation()
        v.check(carrier.trim().length in 2..60, "carrier", "INVALID", "Enter the shipping carrier.")
        v.check(trackingReference.trim().length in 3..100, "trackingReference", "INVALID", "Enter the tracking reference.")
        v.throwIfInvalid()
        val now = clock.instant()
        return mutate(id, expectedVersion) { order ->
            if (order.sellerId != sellerId) {
                if (order.buyerId == sellerId) forbidden("NOT_ORDER_SELLER", "Only the seller can record shipment.") else notFound("Order")
            }
            requireActive(sellerId)
            val next = order.ship(Shipment(carrier.trim(), trackingReference.trim(), now))
                ?: conflict("ORDER_NOT_SHIPPABLE", "Only paid orders can be marked shipped.")
            audit.record(Actor.User(sellerId), "order.shipped", "order", id.toString(), mapOf("carrier" to next.shipment!!.carrier))
            next
        }
    }

    /** SET-009: the buyer confirms delivery of a shipped order. */
    suspend fun confirmDelivery(buyerId: UserId, id: OrderId, expectedVersion: Long?): Order {
        val now = clock.instant()
        return mutate(id, expectedVersion) { order ->
            if (order.buyerId != buyerId) {
                if (order.sellerId == buyerId) forbidden("NOT_ORDER_BUYER", "Only the buyer can confirm delivery.") else notFound("Order")
            }
            requireActive(buyerId)
            val next = order.deliver(now, DeliverySource.BUYER) ?: conflict("ORDER_NOT_SHIPPED", "Only shipped orders can be marked delivered.")
            audit.record(Actor.User(buyerId), "order.delivered", "order", id.toString(), mapOf("source" to "BUYER"))
            next
        }
    }

    /** SET-007: expires unpaid orders past their deadline, one transaction each. */
    fun expireOverdue(limit: Int = 500): Int {
        var changed = 0
        while (changed < limit) {
            val didWork = try {
                tx.blocking {
                    val now = clock.instant()
                    val order = orders.lockNextExpired(now) ?: return@blocking false
                    val next = order.expire(now) ?: return@blocking false
                    save(next.copy(version = order.version + 1), order.version)
                    audit.record(Actor.System("scheduler"), "order.payment-expired", "order", order.id.toString())
                    metrics.increment("gavel.payments", "outcome" to "expired")
                    true
                }
            } catch (e: Exception) {
                log.error("Order expiry step failed; it will be retried on the next run", e)
                false
            }
            if (!didWork) break
            changed++
        }
        return changed
    }

    private suspend fun mutate(id: OrderId, expectedVersion: Long?, change: (Order) -> Order): Order = tx.inTransaction {
        val order = orders.find(id, forUpdate = true) ?: notFound("Order")
        if (expectedVersion != null && expectedVersion != order.version) versionConflict()
        val next = change(order).copy(version = order.version + 1)
        save(next, order.version)
        next
    }

    private fun visible(viewer: UserId, id: OrderId): Order =
        orders.find(id)?.takeIf { it.buyerId == viewer || it.sellerId == viewer } ?: notFound("Order")

    private fun requireActive(userId: UserId) {
        if (participants.find(userId)?.isActive != true) forbidden("ACCOUNT_NOT_ELIGIBLE", "Your account can't update orders right now.")
    }

    private fun save(next: Order, expectedVersion: Long) {
        if (!orders.update(next, expectedVersion)) versionConflict()
    }

    private fun versionConflict(): Nothing = conflict("VERSION_CONFLICT", "The order changed since you loaded it. Reload and try again.")
}
