package com.gavel

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `liveness endpoint returns ok`() = testApplication {
        application { module() }

        val response = client.get("/health/live")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `readiness endpoint returns ready when checks pass`() = testApplication {
        application { module(readinessCheck = { true }) }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("READY", response.bodyAsText())
    }

    @Test
    fun `readiness endpoint returns 503 when checks fail`() = testApplication {
        application { module(readinessCheck = { false }) }

        val response = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("NOT READY", response.bodyAsText())
    }
}
