package com.gavel.kernel

import java.time.Instant
import java.util.UUID

/**
 * Immutable integration event. Payloads are module-owned JSON documents whose
 * shape is fixed per ([type], [payloadVersion]); breaking changes need a new version.
 */
data class EventEnvelope(
    val eventId: UUID,
    val type: String,
    val payloadVersion: Int,
    val aggregateId: String,
    val occurredAt: Instant,
    val correlationId: String,
    val payload: String,
)

/** Records events in the transactional outbox. Must be called inside a transaction. */
interface EventPublisher {
    fun publish(type: String, payloadVersion: Int, aggregateId: String, payload: String)
}

/** Idempotent consumer of one event type, delivered at least once. */
interface EventHandler {
    val name: String
    val eventType: String
    val payloadVersion: Int
    fun handle(event: EventEnvelope)
}

/** Append-only audit trail for security-sensitive and financial state changes. */
interface AuditLog {
    fun record(actor: Actor, action: String, entityType: String, entityId: String, details: Map<String, String?> = emptyMap())
}

sealed interface Actor {
    data class User(val id: UserId) : Actor
    data class System(val component: String) : Actor
    data class Provider(val name: String) : Actor
}

/** Runs a block in one database transaction on a blocking-friendly dispatcher. */
interface Transactor {
    suspend fun <T> inTransaction(block: () -> T): T

    /** For callers that already run on a blocking thread, such as scheduled jobs. */
    fun <T> blocking(block: () -> T): T
}
