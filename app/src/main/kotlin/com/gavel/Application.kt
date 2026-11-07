package com.gavel

import com.gavel.config.AppConfig
import com.gavel.infrastructure.DatabaseConfig
import com.gavel.migration.FlywayConfig
import com.gavel.observability.createMeterRegistry
import com.gavel.observability.installMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.gavel.Application")

fun main() {
    // Fails fast on missing production configuration.
    val config = AppConfig.fromEnv()
    log.info("Starting Gavel API (env={}, port={})", config.environment, config.port)

    val dataSource = DatabaseConfig.createDataSource(config.database)
    FlywayConfig.runMigrations(dataSource)
    Database.connect(dataSource)
    val registry = createMeterRegistry(config.observability)

    embeddedServer(Netty, host = "0.0.0.0", port = config.port, module = {
        module(
            readinessCheck = { withContext(Dispatchers.IO) { DatabaseConfig.isHealthy(dataSource) } },
            meterRegistry = registry,
        )
    }).start(wait = true)
}

/**
 * @param readinessCheck reports whether required infrastructure (the database)
 * is reachable. `/health/ready` returns 503 while it returns false.
 * @param meterRegistry receives HTTP and JVM metrics; tests pass an in-memory one.
 */
fun Application.module(
    readinessCheck: suspend () -> Boolean = { true },
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) {
    installMetrics(meterRegistry)
    routing {
        get("/health/live") {
            call.respondText("OK")
        }
        get("/health/ready") {
            if (readinessCheck()) {
                call.respondText("READY")
            } else {
                call.respondText("NOT READY", status = HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
