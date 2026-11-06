package com.gavel.app

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpContractTest {
    @Test
    fun `health endpoints report liveness and readiness`() = gavelTest {
        assertEquals("Hello, World!", get("/").bodyAsText())
        assertEquals("OK", get("/health/live").bodyAsText())
        val ready = get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("READY", ready.bodyAsText())
    }

    @Test
    fun `errors carry a stable code and the request correlation id`() = gavelTest {
        val response = get("/api/v1/nope") { header("X-Correlation-Id", "corr-12345678") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("corr-12345678", response.headers["X-Correlation-Id"])
        val body = response.json()
        assertEquals("NOT_FOUND", body.code())
        assertEquals("corr-12345678", body.str("correlationId"))
    }

    @Test
    fun `authenticated endpoints reject missing and invalid tokens`() = gavelTest {
        assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/me").status)
        val invalid = get("/api/v1/me", token = "not-a-jwt")
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertEquals("UNAUTHENTICATED", invalid.json().code())
    }

    @Test
    fun `malformed bodies return 400`() = gavelTest {
        val response = post("/api/v1/auth/register", mapOf("email" to 12))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("MALFORMED_REQUEST", response.json().code())
    }

    @Test
    fun `page sizes above the maximum are rejected`() = gavelTest {
        val response = get("/api/v1/listings?limit=51")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("PAGE_SIZE_INVALID", response.json().code())
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/listings?cursor=garbage").status)
    }

    @Test
    fun `CORS allows the configured web origin with credentials`() = gavelTest {
        val preflight = client.options("/api/v1/auth/refresh") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals("http://localhost:3000", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", preflight.headers[HttpHeaders.AccessControlAllowCredentials])

        val foreign = client.options("/api/v1/auth/refresh") {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertTrue(foreign.headers[HttpHeaders.AccessControlAllowOrigin] == null)
    }
}

class OpenApiContractTest {
    private val spec = Gavel::class.java.getResource("/openapi/documentation.yaml")!!.readText()
    private val documented: Set<Pair<String, String>> = run {
        val result = mutableSetOf<Pair<String, String>>()
        var path: String? = null
        for (line in spec.lines()) {
            Regex("^  (/[^:]+):\\s*$").find(line)?.let { path = it.groupValues[1] }
            Regex("^    (get|post|put|patch|delete):").find(line)?.let { m -> path?.let { result += m.groupValues[1].uppercase() to it } }
            if (line.startsWith("components:")) break
        }
        result
    }

    @Test
    fun `the spec documents every API route and nothing else`() = gavelTest {
        val served = gavelRoutes()
        val api = served.filter { it.second.startsWith("/api") || it.second.startsWith("/admin") || it.second.startsWith("/health") }.toSet()
        assertTrue(documented.size >= 30, "parsed ${documented.size} documented operations")
        assertEquals(api.sortedBy { it.second + it.first }, documented.sortedBy { it.second + it.first })
        assertEquals(HttpStatusCode.OK, get("/api/v1/openapi.yaml").status)
    }
}
