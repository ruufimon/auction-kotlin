package com.gavel.observability

import com.gavel.config.AppConfig
import com.gavel.config.ObservabilitySettings
import com.gavel.module
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.registry.otlp.OtlpMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetricsTest {
    @Test
    fun `registry is in-memory when no endpoint is configured`() {
        val r = createMeterRegistry(ObservabilitySettings("gavel-api", "test", null, emptyMap(), 30))
        assertIs<SimpleMeterRegistry>(r)
    }

    @Test
    fun `registry pushes OTLP when an endpoint is configured`() {
        val r = createMeterRegistry(ObservabilitySettings("gavel-api", "test", "https://signoz.example", mapOf("Authorization" to "Bearer x"), 30))
        assertIs<OtlpMeterRegistry>(r)
        r.close()
    }

    @Test
    fun `OTLP headers parse key=value pairs and keep '=' inside values`() {
        val h = AppConfig.parseOtlpHeaders("Authorization=Bearer abc==, x-tenant = gavel ,junk")
        assertEquals(mapOf("Authorization" to "Bearer abc==", "x-tenant" to "gavel"), h)
        assertEquals(emptyMap(), AppConfig.parseOtlpHeaders(null))
    }

    @Test
    fun `http requests are recorded against the route template`() = testApplication {
        val registry = SimpleMeterRegistry()
        application { module(meterRegistry = registry) }
        client.get("/health/live")
        client.get("/health/live")
        val timer = registry.find("http.server.requests").tag("route", "/health/live").timer()
        assertNotNull(timer, "timer for /health/live")
        assertEquals(2, timer.count())
        assertTrue(registry.find("jvm.memory.used").gauges().isNotEmpty(), "JVM binders registered")
    }
}
