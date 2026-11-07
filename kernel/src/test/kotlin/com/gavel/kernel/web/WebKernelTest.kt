package com.gavel.kernel.web

import com.gavel.kernel.auth.TestIdentityProvider
import com.gavel.kernel.auth.TokenPrincipal
import com.gavel.kernel.auth.keycloak
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

class WebKernelTest {
    private val idp = TestIdentityProvider()

    private fun io.ktor.server.testing.ApplicationTestBuilder.app() = application {
        installWebKernel {
            rateLimits { register(RateLimitName("tiny")) { rateLimiter(limit = 2, refillPeriod = 1.minutes) } }
        }
        install(Authentication) { keycloak { issuer = idp.issuer; audience = idp.audience; jwkProvider = idp.jwkProvider } }
        routing {
            get("/boom") { throw ApiException.unprocessable("SOMETHING_WRONG", "nope", listOf(ErrorDetail(field = "x", code = "REQUIRED"))) }
            get("/crash") { throw IllegalStateException("secret internals") }
            authenticate { get("/who") { call.respondText(call.principal<TokenPrincipal>()!!.subject) } }
            rateLimit(RateLimitName("tiny")) { get("/limited") { call.respondText("ok") } }
        }
    }

    @Test
    fun `ApiException becomes the uniform error body with a correlation id`() = testApplication {
        app()
        val response = client.get("/boom") { header(HttpHeaders.XRequestId, "corr-123") }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals(ApiError("corr-123", "SOMETHING_WRONG", "nope", listOf(ErrorDetail(field = "x", code = "REQUIRED"))), body)
    }

    @Test
    fun `unexpected exceptions are 500 INTERNAL_ERROR without leaking the message`() = testApplication {
        app()
        val response = client.get("/crash")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("INTERNAL_ERROR", body.code)
        assertEquals(false, response.bodyAsText().contains("secret internals"))
        assertNotNull(body.correlationId)
    }

    @Test
    fun `missing bearer token is 401 TOKEN_MISSING and a valid one yields the principal`() = testApplication {
        app()
        val anonymous = client.get("/who")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("TOKEN_MISSING", Json.decodeFromString<ApiError>(anonymous.bodyAsText()).code)

        val authed = client.get("/who") { header(HttpHeaders.Authorization, "Bearer ${idp.mint(subject = "kc-42")}") }
        assertEquals(HttpStatusCode.OK, authed.status)
        assertEquals("kc-42", authed.bodyAsText())
    }

    @Test
    fun `expired token is 401 TOKEN_EXPIRED through the Ktor pipeline`() = testApplication {
        app()
        val token = idp.mint(issuedAt = java.time.Instant.now().minusSeconds(3600), ttl = java.time.Duration.ofMinutes(15))
        val response = client.get("/who") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("TOKEN_EXPIRED", Json.decodeFromString<ApiError>(response.bodyAsText()).code)
    }

    @Test
    fun `rate limited requests get 429 RATE_LIMITED with Retry-After`() = testApplication {
        app()
        repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/limited").status) }
        val limited = client.get("/limited")

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", Json.decodeFromString<ApiError>(limited.bodyAsText()).code)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }
}
