package com.gavel.kernel.audit

import com.gavel.kernel.persistence.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Who performed an audited action. */
sealed interface Actor {
    data class User(val id: UUID) : Actor
    data object System : Actor
    data object Scheduler : Actor
}

/** Append-only record of security-relevant and financial actions (requirement §2.2). */
interface AuditLog {
    fun record(
        actor: Actor,
        action: String,
        aggregateType: String,
        aggregateId: UUID,
        metadata: Map<String, String?> = emptyMap(),
        correlationId: UUID? = null,
    )
}

object AuditLogTable : Table("audit_log") {
    val id = uuid("id").autoGenerate()
    val actorId = uuid("actor_id").nullable()
    val actorType = varchar("actor_type", 32)
    val action = varchar("action", 128)
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = uuid("aggregate_id")
    val metadata = jsonb("metadata").nullable()
    val correlationId = uuid("correlation_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Writes `audit_log` rows inside the caller's current transaction. */
class DatabaseAuditLog(private val clock: Clock = Clock.systemUTC()) : AuditLog {
    override fun record(
        actor: Actor,
        action: String,
        aggregateType: String,
        aggregateId: UUID,
        metadata: Map<String, String?>,
        correlationId: UUID?,
    ) {
        checkNotNull(TransactionManager.currentOrNull()) { "record() must run inside the audited transaction" }
        AuditLogTable.insert {
            it[actorId] = (actor as? Actor.User)?.id
            it[actorType] = when (actor) {
                is Actor.User -> "user"
                Actor.System -> "system"
                Actor.Scheduler -> "scheduler"
            }
            it[AuditLogTable.action] = action
            it[AuditLogTable.aggregateType] = aggregateType
            it[AuditLogTable.aggregateId] = aggregateId
            it[AuditLogTable.metadata] = if (metadata.isEmpty()) null else Json.encodeToString(
                JsonObject.serializer(),
                JsonObject(metadata.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) }),
            )
            it[AuditLogTable.correlationId] = correlationId
            it[createdAt] = clock.instant()
        }
    }
}
