package com.gavel.observability

import com.gavel.config.ObservabilitySettings
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("com.gavel.observability.Metrics")

/**
 * Builds the meter registry: an OTLP/HTTP push registry when an endpoint is
 * configured, otherwise an in-memory one so the rest of the app is unchanged.
 */
fun createMeterRegistry(settings: ObservabilitySettings, version: String = appVersion()): MeterRegistry {
    if (!settings.enabled) {
        log.info("Metrics export disabled (OTEL_EXPORTER_OTLP_ENDPOINT not set)")
        return SimpleMeterRegistry()
    }
    val config = object : OtlpConfig {
        override fun get(key: String): String? = null
        override fun url(): String = settings.metricsUrl!!
        override fun headers(): Map<String, String> = settings.headers
        override fun step(): Duration = Duration.ofSeconds(settings.exportIntervalSeconds)
        override fun resourceAttributes(): Map<String, String> = mapOf(
            "service.name" to settings.serviceName,
            "service.version" to version,
            "deployment.environment" to settings.environment,
        )
    }
    log.info("Metrics export enabled -> {} every {}s", settings.metricsUrl, settings.exportIntervalSeconds)
    return OtlpMeterRegistry(config, Clock.SYSTEM)
}

/**
 * HTTP server metrics with route templates as the `route` tag (never raw paths
 * with ids) and latency histograms so p95/p99 can be computed in the backend.
 */
fun Application.installMetrics(registry: MeterRegistry) {
    install(MicrometerMetrics) {
        this.registry = registry
        metricName = "http.server.requests"
        distributionStatisticConfig = DistributionStatisticConfig.builder()
            .percentilesHistogram(true)
            .serviceLevelObjectives(
                Duration.ofMillis(100).toNanos().toDouble(),
                Duration.ofMillis(300).toNanos().toDouble(),
                Duration.ofMillis(800).toNanos().toDouble(),
                Duration.ofMillis(1500).toNanos().toDouble(),
            )
            .build()
    }
}

private fun appVersion(): String =
    object {}.javaClass.`package`?.implementationVersion ?: System.getenv("APP_VERSION") ?: "dev"
