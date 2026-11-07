package com.gavel.kernel.events

import java.time.Instant
import java.util.UUID

/**
 * Envelope around every domain event. See DOMAIN_EVENTS.md.
 *
 * [aggregateId] is the owning row's internal UUID (the outbox column is UUID);
 * public identifiers such as `usr_…` travel inside the payload.
 */
data class EventEnvelope(
    val eventId: UUID,
    val type: String,
    val payloadVersion: Int,
    val aggregateType: String,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val correlationId: UUID?,
    val payload: String,
)

/**
 * Single writer of the event stream. Implementations persist the event inside the
 * caller's current database transaction so state change and event commit together.
 */
interface EventPublisher {
    fun publish(
        type: String,
        payloadVersion: Int,
        aggregateType: String,
        aggregateId: UUID,
        payload: String,
        correlationId: UUID? = null,
    )
}

/** A feature's subscription to one event type and payload version. Must be idempotent. */
interface EventHandler {
    /** Idempotency key for delivery tracking; unique across the application. */
    val name: String
    val eventType: String
    val payloadVersion: Int
    fun handle(event: EventEnvelope)
}
