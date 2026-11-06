package com.gavel.settlement.infrastructure

import com.gavel.kernel.BidId
import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.Cursor
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.OrderId
import com.gavel.kernel.Page
import com.gavel.kernel.PageRequest
import com.gavel.kernel.UserId
import com.gavel.onboarding.api.ShippingContact
import com.gavel.settlement.application.OrderRepository
import com.gavel.settlement.application.OrderRole
import com.gavel.settlement.domain.AttemptStatus
import com.gavel.settlement.domain.DeliverySource
import com.gavel.settlement.domain.Order
import com.gavel.settlement.domain.OrderStatus
import com.gavel.settlement.domain.PaymentAttempt
import com.gavel.settlement.domain.Shipment
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

internal object OrdersTable : Table("settlement.orders") {
    val id = javaUUID("id")
    val listingId = javaUUID("listing_id")
    val title = varchar("title", 80)
    val winningBidId = javaUUID("winning_bid_id")
    val buyerId = javaUUID("buyer_id")
    val sellerId = javaUUID("seller_id")
    val currency = varchar("currency", 3)
    val itemPriceMinor = long("item_price_minor")
    val shippingPriceMinor = long("shipping_price_minor")
    val status = varchar("status", 20)
    val paymentDeadline = timestamp("payment_deadline")
    val shipName = varchar("ship_name", 60).nullable()
    val shipPhone = varchar("ship_phone", 32).nullable()
    val shipLine1 = varchar("ship_line1", 200).nullable()
    val shipLine2 = varchar("ship_line2", 200).nullable()
    val shipCity = varchar("ship_city", 100).nullable()
    val shipRegion = varchar("ship_region", 100).nullable()
    val shipPostalCode = varchar("ship_postal_code", 20).nullable()
    val shipCountry = varchar("ship_country", 2).nullable()
    val paidAt = timestamp("paid_at").nullable()
    val carrier = varchar("carrier", 60).nullable()
    val trackingReference = varchar("tracking_reference", 100).nullable()
    val shippedAt = timestamp("shipped_at").nullable()
    val deliveredAt = timestamp("delivered_at").nullable()
    val deliverySource = varchar("delivery_source", 16).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

internal object PaymentAttemptsTable : Table("settlement.payment_attempts") {
    val id = javaUUID("id")
    val orderId = javaUUID("order_id")
    val attemptNumber = integer("attempt_number")
    val provider = varchar("provider", 40)
    val providerReference = varchar("provider_reference", 200)
    val checkoutUrl = varchar("checkout_url", 2000)
    val amountMinor = long("amount_minor")
    val currency = varchar("currency", 3)
    val status = varchar("status", 16)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ProviderEventsTable : Table("settlement.provider_events") {
    val provider = varchar("provider", 40)
    val eventId = varchar("event_id", 200)
    val receivedAt = timestamp("received_at")
    override val primaryKey = PrimaryKey(provider, eventId)
}

internal class ExposedOrderRepository(private val clock: java.time.Clock) : OrderRepository {
    private val o = OrdersTable
    private val p = PaymentAttemptsTable

    override fun insertIfAbsent(order: Order): Boolean =
        o.insertIgnore {
            it[id] = order.id.value
            it[listingId] = order.listingId.value
            it[title] = order.title
            it[winningBidId] = order.winningBidId.value
            it[buyerId] = order.buyerId.value
            it[sellerId] = order.sellerId.value
            it[currency] = order.itemPrice.currency.code
            it[itemPriceMinor] = order.itemPrice.amountMinor
            it[shippingPriceMinor] = order.shippingPrice.amountMinor
            it[paymentDeadline] = order.paymentDeadline
            it[createdAt] = order.createdAt
            write(it, order)
        }.insertedCount == 1

    override fun update(order: Order, expectedVersion: Long): Boolean =
        o.update({ (o.id eq order.id.value) and (o.version eq expectedVersion) }) { write(it, order) } == 1

    private fun write(it: UpdateBuilder<*>, order: Order) = with(o) {
        it[status] = order.status.name
        it[shipName] = order.shipTo?.name
        it[shipPhone] = order.shipTo?.phone
        it[shipLine1] = order.shipTo?.line1
        it[shipLine2] = order.shipTo?.line2
        it[shipCity] = order.shipTo?.city
        it[shipRegion] = order.shipTo?.region
        it[shipPostalCode] = order.shipTo?.postalCode
        it[shipCountry] = order.shipTo?.country
        it[paidAt] = order.paidAt
        it[carrier] = order.shipment?.carrier
        it[trackingReference] = order.shipment?.trackingReference
        it[shippedAt] = order.shipment?.shippedAt
        it[deliveredAt] = order.deliveredAt
        it[deliverySource] = order.deliverySource?.name
        it[updatedAt] = clock.instant()
        it[version] = order.version
    }

    override fun find(id: OrderId, forUpdate: Boolean): Order? =
        o.selectAll().where { o.id eq id.value }.let { if (forUpdate) it.forUpdate() else it }.singleOrNull()?.toOrder()

    override fun forParticipant(userId: UserId, role: OrderRole, page: PageRequest): Page<Order> {
        val owner = if (role == OrderRole.BUYER) o.buyerId else o.sellerId
        val after = page.cursor?.let { raw -> Cursor.decode(raw, 2) { Instant.parse(it[0]) to UUID.fromString(it[1]) } }
        val rows = o.selectAll()
            .where {
                val mine = owner eq userId.value
                if (after == null) mine else mine and ((o.createdAt less after.first) or ((o.createdAt eq after.first) and (o.id less after.second)))
            }
            .orderBy(o.createdAt to SortOrder.DESC, o.id to SortOrder.DESC)
            .limit(page.limit + 1)
            .map { it.toOrder() }
        val items = rows.take(page.limit)
        return Page(items, if (rows.size > page.limit) items.last().let { Cursor.encode(it.createdAt.toString(), it.id.toString()) } else null)
    }

    override fun lockNextExpired(now: Instant): Order? =
        o.selectAll()
            .where { (o.status eq OrderStatus.PAYMENT_PENDING.name) and (o.paymentDeadline lessEq now) }
            .orderBy(o.paymentDeadline to SortOrder.ASC)
            .limit(1)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .singleOrNull()?.toOrder()

    override fun attempts(orderId: OrderId): List<PaymentAttempt> =
        p.selectAll().where { p.orderId eq orderId.value }.orderBy(p.attemptNumber).map { it.toAttempt() }

    override fun insertAttemptIfAbsent(attempt: PaymentAttempt): Boolean =
        p.insertIgnore {
            it[id] = attempt.id
            it[orderId] = attempt.orderId.value
            it[attemptNumber] = attempt.attemptNumber
            it[provider] = attempt.provider
            it[providerReference] = attempt.providerReference
            it[checkoutUrl] = attempt.checkoutUrl
            it[amountMinor] = attempt.amount.amountMinor
            it[currency] = attempt.amount.currency.code
            it[status] = attempt.status.name
            it[createdAt] = attempt.createdAt
            it[updatedAt] = attempt.createdAt
        }.insertedCount == 1

    override fun findAttemptByReference(provider: String, reference: String, forUpdate: Boolean): PaymentAttempt? =
        p.selectAll().where { (p.provider eq provider) and (p.providerReference eq reference) }
            .let { if (forUpdate) it.forUpdate() else it }.singleOrNull()?.toAttempt()

    override fun updateAttemptStatus(id: UUID, status: AttemptStatus, now: Instant) {
        p.update({ p.id eq id }) {
            it[this.status] = status.name
            it[updatedAt] = now
        }
    }

    override fun recordProviderEvent(provider: String, eventId: String, now: Instant): Boolean =
        ProviderEventsTable.insertIgnore {
            it[this.provider] = provider
            it[this.eventId] = eventId
            it[receivedAt] = now
        }.insertedCount == 1

    private fun ResultRow.toOrder(): Order {
        val currency = CurrencyCode(this[o.currency])
        return Order(
            id = OrderId(this[o.id]),
            listingId = ListingId(this[o.listingId]),
            title = this[o.title],
            winningBidId = BidId(this[o.winningBidId]),
            buyerId = UserId(this[o.buyerId]),
            sellerId = UserId(this[o.sellerId]),
            itemPrice = Money(this[o.itemPriceMinor], currency),
            shippingPrice = Money(this[o.shippingPriceMinor], currency),
            status = OrderStatus.valueOf(this[o.status]),
            paymentDeadline = this[o.paymentDeadline],
            shipTo = this[o.shipLine1]?.let {
                ShippingContact(this[o.shipName]!!, this[o.shipPhone], it, this[o.shipLine2], this[o.shipCity]!!, this[o.shipRegion]!!, this[o.shipPostalCode]!!, this[o.shipCountry]!!)
            },
            paidAt = this[o.paidAt],
            shipment = this[o.carrier]?.let { Shipment(it, this[o.trackingReference]!!, this[o.shippedAt]!!) },
            deliveredAt = this[o.deliveredAt],
            deliverySource = this[o.deliverySource]?.let(DeliverySource::valueOf),
            createdAt = this[o.createdAt],
            version = this[o.version],
        )
    }

    private fun ResultRow.toAttempt() = PaymentAttempt(
        id = this[p.id],
        orderId = OrderId(this[p.orderId]),
        attemptNumber = this[p.attemptNumber],
        provider = this[p.provider],
        providerReference = this[p.providerReference],
        checkoutUrl = this[p.checkoutUrl],
        amount = Money(this[p.amountMinor], CurrencyCode(this[p.currency])),
        status = AttemptStatus.valueOf(this[p.status]),
        createdAt = this[p.createdAt],
    )
}
