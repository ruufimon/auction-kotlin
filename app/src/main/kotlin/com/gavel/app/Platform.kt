package com.gavel.app

import com.gavel.kernel.Actor
import com.gavel.kernel.AuditLog
import com.gavel.kernel.Correlation
import com.gavel.kernel.EventEnvelope
import com.gavel.kernel.EventHandler
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.Metrics
import com.gavel.kernel.Transactor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

private fun exec(sql: String, vararg args: Pair<IColumnType<*>, Any?>) =
    TransactionManager.current().exec(sql, args.toList())

/** Writes events into platform.outbox_events inside the caller's transaction. */
class OutboxEventPublisher(private val clock: Clock) : EventPublisher {
    override fun publish(type: String, payloadVersion: Int, aggregateId: String, payload: String) {
        val now = clock.instant()
        exec(
            "INSERT INTO platform.outbox_events (id, event_type, payload_version, aggregate_id, occurred_at, correlation_id, payload, status, next_attempt_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'PENDING', ?)",
            UUIDColumnType() to UUID.randomUUID(), TextColumnType() to type, IntegerColumnType() to payloadVersion,
            TextColumnType() to aggregateId, JavaInstantColumnType() to now, TextColumnType() to Correlation.current(),
            TextColumnType() to payload, JavaInstantColumnType() to now,
        )
    }
}

class DatabaseAuditLog(private val clock: Clock) : AuditLog {
    override fun record(actor: Actor, action: String, entityType: String, entityId: String, details: Map<String, String?>) {
        val (type, id) = when (actor) {
            is Actor.User -> "USER" to actor.id.toString()
            is Actor.System -> "SYSTEM" to actor.component
            is Actor.Provider -> "PROVIDER" to actor.name
        }
        val json = JsonObject(details.mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull }).toString()
        exec(
            "INSERT INTO platform.audit_log (occurred_at, actor_type, actor_id, action, entity_type, entity_id, details, correlation_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
            JavaInstantColumnType() to clock.instant(), TextColumnType() to type, TextColumnType() to id, TextColumnType() to action,
            TextColumnType() to entityType, TextColumnType() to entityId, TextColumnType() to json, TextColumnType() to Correlation.current(),
        )
    }
}

/**
 * At-least-once delivery from the outbox to in-process handlers. Each event is
 * claimed with SKIP LOCKED, so several instances can dispatch concurrently, and
 * handled in the same transaction that marks it delivered. Failures back off
 * exponentially and end in DEAD after [maxAttempts].
 */
class OutboxDispatcher(
    private val tx: Transactor,
    handlers: List<EventHandler>,
    private val metrics: Metrics,
    private val clock: Clock,
    private val maxAttempts: Int = 10,
    private val baseBackoff: Duration = Duration.ofSeconds(2),
) {
    private val log = LoggerFactory.getLogger(OutboxDispatcher::class.java)
    private val handlersByType = handlers.groupBy { it.eventType to it.payloadVersion }
    val pending = AtomicLong()
    val dead = AtomicLong()

    /** Dispatches until no due events remain or [limit] is reached. Returns the number handled. */
    fun dispatchDue(limit: Int = 1000): Int {
        var handled = 0
        while (handled < limit) {
            val claimed: Claimed = try {
                tx.blocking { deliverNext() }
            } catch (e: HandlerFailure) {
                recordFailure(e.event, e.cause!!)
                Claimed.Failed
            }
            if (claimed == Claimed.None) break
            handled++
        }
        refreshGauges()
        return handled
    }

    private sealed interface Claimed {
        data object None : Claimed
        data object Delivered : Claimed
        data object Failed : Claimed
    }

    private class HandlerFailure(val event: EventEnvelope, cause: Throwable) : RuntimeException(cause)

    private fun deliverNext(): Claimed {
        val now = clock.instant()
        val event = nextDue(now) ?: return Claimed.None
        val handlers = handlersByType[event.type to event.payloadVersion].orEmpty()
        Correlation.with(event.correlationId) {
            for (handler in handlers) {
                try {
                    handler.handle(event)
                } catch (e: Exception) {
                    throw HandlerFailure(event, e)
                }
            }
        }
        exec(
            "UPDATE platform.outbox_events SET status = 'DELIVERED', delivered_at = ?, attempts = attempts + 1 WHERE id = ?",
            JavaInstantColumnType() to now, UUIDColumnType() to event.eventId,
        )
        metrics.record("gavel.outbox.delivery.lag", Duration.between(event.occurredAt, now), "type" to event.type)
        return Claimed.Delivered
    }

    private fun nextDue(now: Instant): EventEnvelope? {
        var envelope: EventEnvelope? = null
        TransactionManager.current().exec(
            "SELECT id, event_type, payload_version, aggregate_id, occurred_at, correlation_id, payload::text FROM platform.outbox_events " +
                "WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY next_attempt_at, occurred_at LIMIT 1 FOR UPDATE SKIP LOCKED",
            listOf(JavaInstantColumnType() to now),
        ) { rs ->
            if (rs.next()) {
                envelope = EventEnvelope(
                    eventId = rs.getObject(1, UUID::class.java),
                    type = rs.getString(2),
                    payloadVersion = rs.getInt(3),
                    aggregateId = rs.getString(4),
                    occurredAt = rs.getTimestamp(5).toInstant(),
                    correlationId = rs.getString(6),
                    payload = rs.getString(7),
                )
            }
        }
        return envelope
    }

    private fun recordFailure(event: EventEnvelope, error: Throwable) {
        log.warn("Event delivery failed for type {} (event {}); scheduling retry", event.type, event.eventId, error)
        metrics.increment("gavel.outbox.retries", "type" to event.type)
        tx.blocking {
            var attempts = 0
            TransactionManager.current().exec(
                "SELECT attempts FROM platform.outbox_events WHERE id = ? FOR UPDATE", listOf(UUIDColumnType() to event.eventId),
            ) { rs -> if (rs.next()) attempts = rs.getInt(1) + 1 }
            val deadNow = attempts >= maxAttempts
            val delay = baseBackoff.multipliedBy(1L shl min(attempts - 1, 16)).coerceAtMost(Duration.ofHours(1))
            exec(
                "UPDATE platform.outbox_events SET attempts = ?, status = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                IntegerColumnType() to attempts, TextColumnType() to if (deadNow) "DEAD" else "PENDING",
                JavaInstantColumnType() to clock.instant() + delay,
                TextColumnType() to "${error::class.simpleName}: ${error.message}".take(1000), UUIDColumnType() to event.eventId,
            )
            if (deadNow) {
                log.error("Event {} of type {} moved to DEAD after {} attempts", event.eventId, event.type, attempts)
                metrics.increment("gavel.outbox.dead_lettered", "type" to event.type)
            }
        }
    }

    /** Removes delivered events older than [retention]; DEAD events stay for diagnosis. */
    fun pruneDelivered(retention: Duration = Duration.ofDays(7)): Int = tx.blocking {
        TransactionManager.current().exec(
            "DELETE FROM platform.outbox_events WHERE status = 'DELIVERED' AND delivered_at < ?",
            listOf(JavaInstantColumnType() to clock.instant() - retention),
        )
        0
    }

    private fun refreshGauges() {
        runCatching {
            tx.blocking {
                TransactionManager.current().exec(
                    "SELECT (SELECT count(*) FROM platform.outbox_events WHERE status = 'PENDING'), " +
                        "(SELECT count(*) FROM platform.outbox_events WHERE status = 'DEAD')",
                ) { rs ->
                    if (rs.next()) {
                        pending.set(rs.getLong(1))
                        dead.set(rs.getLong(2))
                    }
                }
            }
        }
    }
}
