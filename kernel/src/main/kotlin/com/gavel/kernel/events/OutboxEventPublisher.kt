package com.gavel.kernel.events

import com.gavel.kernel.persistence.jsonb
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Maps the shared `event_outbox` table (kernel/src/main/resources/db/migration/shared). */
object EventOutboxTable : Table("event_outbox") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id")
    val eventType = varchar("event_type", 255)
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = uuid("aggregate_id")
    val payloadVersion = integer("payload_version")
    val payload = jsonb("payload")
    val correlationId = uuid("correlation_id").nullable()
    val causationId = uuid("causation_id").nullable()
    val publishedAt = timestamp("published_at").nullable()
    val deliveryAttempts = integer("delivery_attempts")
    val lastDeliveryError = text("last_delivery_error").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Transactional-outbox publisher: writes the event row in the caller's current
 * Exposed transaction. Calling it outside a transaction is a programming error.
 */
class OutboxEventPublisher(private val clock: Clock = Clock.systemUTC()) : EventPublisher {
    override fun publish(
        type: String,
        payloadVersion: Int,
        aggregateType: String,
        aggregateId: UUID,
        payload: String,
        correlationId: UUID?,
    ) {
        checkNotNull(TransactionManager.currentOrNull()) { "publish() must run inside the state-changing transaction" }
        val now = clock.instant()
        EventOutboxTable.insert {
            it[eventId] = UUID.randomUUID()
            it[eventType] = type
            it[EventOutboxTable.aggregateType] = aggregateType
            it[EventOutboxTable.aggregateId] = aggregateId
            it[EventOutboxTable.payloadVersion] = payloadVersion
            it[EventOutboxTable.payload] = payload
            it[EventOutboxTable.correlationId] = correlationId
            it[deliveryAttempts] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
    }
}
