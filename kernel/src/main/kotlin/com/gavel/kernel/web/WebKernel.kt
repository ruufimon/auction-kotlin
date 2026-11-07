package com.gavel.kernel.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitConfig
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("com.gavel.kernel.web")

/** JSON settings shared by every endpoint: tolerant of unknown keys, omits nulls. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

class WebKernelConfig {
    internal var rateLimits: RateLimitConfig.() -> Unit = {}
    internal var hasRateLimits = false

    /** Register named rate limiters that routes refer to with `rateLimit(RateLimitName("…"))`. */
    fun rateLimits(configure: RateLimitConfig.() -> Unit) {
        rateLimits = configure
        hasRateLimits = true
    }
}

/**
 * Cross-cutting HTTP behaviour (requirement §6.5): correlation ids, JSON, uniform
 * error bodies, and named rate limiters. Install once per application, before routes.
 */
fun Application.installWebKernel(configure: WebKernelConfig.() -> Unit = {}) {
    val config = WebKernelConfig().apply(configure)

    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= 128 }
    }
    install(ContentNegotiation) { json(ApiJson) }
    // The plugin refuses to install with no limiter registered.
    if (config.hasRateLimits) install(RateLimit) { config.rateLimits(this) }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message, cause.details)
        }
        exception<BadRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "MALFORMED_REQUEST", cause.rootMessage())
        }
        exception<SerializationException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "MALFORMED_REQUEST", cause.rootMessage())
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error (correlationId={})", call.callId, cause)
            call.respondError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Unexpected error")
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondError(status, "RATE_LIMITED", "Too many requests; retry after the Retry-After interval")
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondError(status, "NOT_FOUND", "No such resource")
        }
    }
}

private fun Throwable.rootMessage(): String =
    generateSequence(this) { it.cause }.last().message?.take(300) ?: "Request body could not be read"

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: List<ErrorDetail> = emptyList(),
) {
    respond(status, ApiError(callId ?: "none", code, message, details))
}
