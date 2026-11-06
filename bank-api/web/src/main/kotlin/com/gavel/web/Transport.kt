package com.gavel.web

import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.DomainException
import com.gavel.kernel.ErrorKind
import com.gavel.kernel.FieldError
import com.gavel.kernel.Money
import com.gavel.kernel.UserId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Name of the bearer-token authentication provider installed by the app. */
const val AUTH_USER = "user"

/** Authenticated caller; the app only issues it for active accounts. */
data class UserPrincipal(val userId: UserId)

fun ApplicationCall.currentUserId(): UserId =
    principal<UserPrincipal>()?.userId
        ?: throw DomainException(ErrorKind.UNAUTHENTICATED, "UNAUTHENTICATED", "Sign in to continue.")

fun ApplicationCall.optionalUserId(): UserId? = principal<UserPrincipal>()?.userId

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val correlationId: String,
    val fields: List<ApiFieldError> = emptyList(),
)

@Serializable
data class ApiFieldError(val field: String, val code: String, val message: String)

fun FieldError.toApi() = ApiFieldError(field, code, message)

@Serializable
data class MoneyDto(val amountMinor: Long, val currency: String) {
    fun toMoney(field: String): Money {
        val code = runCatching { CurrencyCode(currency) }.getOrNull() ?: throw malformed(field, "Currency must be an ISO 4217 code.")
        return Money(amountMinor, code)
    }
}

fun Money.toDto() = MoneyDto(amountMinor, currency.code)

@Serializable
data class PageDto<T>(val items: List<T>, val nextCursor: String?)

/** Receives a JSON body, reporting decoding problems as a stable 400 error. */
suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T =
    try {
        receive<T>()
    } catch (e: DomainException) {
        throw e
    } catch (e: Exception) {
        val detail = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is SerializationException }?.message
        throw DomainException(ErrorKind.MALFORMED, "MALFORMED_REQUEST", detail ?: "The request body is not valid JSON for this operation.")
    }

fun ApplicationCall.queryInt(name: String): Int? =
    request.queryParameters[name]?.let { it.toIntOrNull() ?: throw malformed(name, "$name must be an integer.") }

fun ApplicationCall.queryLong(name: String): Long? =
    request.queryParameters[name]?.let { it.toLongOrNull() ?: throw malformed(name, "$name must be an integer.") }

fun ApplicationCall.queryInstant(name: String): Instant? =
    request.queryParameters[name]?.let { parseInstant(it, name) }

fun ApplicationCall.query(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotBlank() }

fun ApplicationCall.pathParam(name: String): String =
    parameters[name] ?: throw malformed(name, "$name is required.")

fun parseInstant(raw: String, field: String): Instant =
    try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        throw malformed(field, "$field must be an ISO 8601 UTC timestamp, such as 2026-11-03T12:00:00Z.")
    }

fun malformed(field: String, message: String) =
    DomainException(ErrorKind.MALFORMED, "VALIDATION_FAILED", message, listOf(FieldError(field, "INVALID", message)))

/** Reads a required Idempotency-Key header (1–200 visible ASCII characters). */
fun ApplicationCall.idempotencyKey(): String {
    val key = request.headers["Idempotency-Key"]?.trim()
    if (key.isNullOrEmpty() || key.length > 200 || key.any { it.code !in 0x21..0x7E }) {
        throw DomainException(
            ErrorKind.MALFORMED, "IDEMPOTENCY_KEY_REQUIRED",
            "Send an Idempotency-Key header of 1–200 visible ASCII characters.",
        )
    }
    return key
}

/** Optional expected resource version from If-Match (bare or quoted integer). */
fun ApplicationCall.expectedVersion(): Long? =
    request.headers["If-Match"]?.trim()?.removePrefix("W/")?.trim('"')?.let {
        it.toLongOrNull() ?: throw malformed("If-Match", "If-Match must carry a resource version.")
    }
