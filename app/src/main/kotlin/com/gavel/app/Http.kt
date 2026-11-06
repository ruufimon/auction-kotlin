package com.gavel.app

import com.gavel.kernel.Correlation
import com.gavel.kernel.DomainException
import com.gavel.kernel.ErrorKind
import com.gavel.kernel.Secrets
import com.gavel.kernel.UserId
import com.gavel.web.AUTH_USER
import com.gavel.web.ApiError
import com.gavel.web.UserPrincipal
import com.gavel.web.toApi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

val AUTH_RATE_LIMIT = RateLimitName("auth")
val WEBHOOK_RATE_LIMIT = RateLimitName("webhooks")
const val AUTH_ADMIN = "operator"

data class RateLimits(val authPerMinute: Int = 20, val webhooksPerMinute: Int = 600)

private val log = LoggerFactory.getLogger("com.gavel.app.Http")

fun ApplicationCall.correlationId(): String = callId ?: Correlation.current()

fun Application.installHttp(gavel: Gavel, rateLimits: RateLimits) {
    val config = gavel.config

    install(CallId) {
        retrieveFromHeader(Correlation.HEADER)
        generate { UUID.randomUUID().toString() }
        verify { id -> id.length in 8..100 && id.all { it.isLetterOrDigit() || it in "-_." } }
        replyToHeader(Correlation.HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc(Correlation.MDC_KEY)
        disableDefaultColors()
        filter { it.request.path().startsWith("/api") || it.request.path().startsWith("/admin") }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true })
    }
    install(MicrometerMetrics) { registry = gavel.meterRegistry }
    install(CORS) {
        config.webOrigins.forEach { origin ->
            val (scheme, host) = origin.split("://", limit = 2).let { it[0] to it[1] }
            allowHost(host, schemes = listOf(scheme))
        }
        allowCredentials = true
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.IfMatch)
        allowHeader("Idempotency-Key")
        allowHeader(Correlation.HEADER)
        exposeHeader(Correlation.HEADER)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader("Idempotent-Replayed")
        maxAgeInSeconds = 600
    }
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = rateLimits.authPerMinute, refillPeriod = 1.minutes)
            requestKey { it.clientAddress() }
        }
        register(WEBHOOK_RATE_LIMIT) {
            rateLimiter(limit = rateLimits.webhooksPerMinute, refillPeriod = 1.minutes)
            requestKey { it.clientAddress() }
        }
    }
    install(Authentication) {
        jwt(AUTH_USER) {
            verifier(gavel.onboarding.jwtVerifier)
            validate { credential ->
                val id = credential.payload.subject?.let { runCatching { UserId(UUID.fromString(it)) }.getOrNull() }
                if (id != null && gavel.onboarding.isActive(id)) UserPrincipal(id) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHENTICATED", "Sign in to continue.", call.correlationId()))
            }
        }
        config.adminToken?.let { token ->
            bearer(AUTH_ADMIN) {
                authenticate { if (Secrets.constantTimeEquals(it.token, token)) UserIdPrincipal("operator") else null }
            }
        }
    }
    install(StatusPages) {
        exception<DomainException> { call, e ->
            call.respond(e.kind.status(), ApiError(e.code, e.message ?: e.code, call.correlationId(), e.fields.map { it.toApi() }))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("MALFORMED_REQUEST", e.message ?: "The request is malformed.", call.correlationId()))
        }
        exception<ExposedSQLException> { call, e ->
            when (e.sqlState) {
                "23505" -> call.respond(HttpStatusCode.Conflict, ApiError("CONFLICT", "The resource already exists or changed concurrently.", call.correlationId()))
                "40001", "40P01" -> call.respond(HttpStatusCode.Conflict, ApiError("CONCURRENT_UPDATE", "The request conflicted with another update. Retry it.", call.correlationId()))
                else -> internalError(call, e)
            }
        }
        exception<Throwable> { call, e -> internalError(call, e) }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "No resource exists at this path.", call.correlationId()))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respond(HttpStatusCode.MethodNotAllowed, ApiError("METHOD_NOT_ALLOWED", "This method is not supported here.", call.correlationId()))
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ApiError("RATE_LIMITED", "Too many requests. Try again shortly.", call.correlationId()))
        }
    }
}

private suspend fun internalError(call: ApplicationCall, e: Throwable) {
    log.error("Unhandled error", e)
    call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "Something went wrong. Quote the correlation ID if you contact support.", call.correlationId()))
}

fun ErrorKind.status(): HttpStatusCode = when (this) {
    ErrorKind.MALFORMED -> HttpStatusCode.BadRequest
    ErrorKind.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
    ErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
    ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
    ErrorKind.CONFLICT -> HttpStatusCode.Conflict
    ErrorKind.RULE_VIOLATION -> HttpStatusCode.UnprocessableEntity
}

/** DigitalOcean's edge sets do-connecting-ip; fall back to the socket address locally. */
private fun ApplicationCall.clientAddress(): String =
    request.headers["do-connecting-ip"] ?: request.origin.remoteHost
