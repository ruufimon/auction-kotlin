package com.gavel

import com.gavel.config.AppConfig
import com.gavel.infrastructure.DatabaseConfig
import com.gavel.kernel.audit.DatabaseAuditLog
import com.gavel.kernel.auth.IdentityProviderSettings
import com.gavel.kernel.auth.NoKeysJwkProvider
import com.gavel.kernel.auth.keycloak
import com.gavel.kernel.events.OutboxEventPublisher
import com.gavel.kernel.web.installWebKernel
import com.gavel.migration.FlywayConfig
import com.gavel.observability.createMeterRegistry
import com.gavel.observability.installMetrics
import com.gavel.onboarding.UserOnboarding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.gavel.Application")

fun main() {
    // Fails fast on missing production configuration.
    val config = AppConfig.fromEnv()
    log.info("Starting Gavel API (env={}, port={}, issuer={})", config.environment, config.port, config.identity.issuer)

    val dataSource = DatabaseConfig.createDataSource(config.database)
    FlywayConfig.runMigrations(dataSource)
    val database = Database.connect(dataSource)
    val registry = createMeterRegistry(config.observability)

    val onboarding = UserOnboarding(
        settings = config.onboarding,
        database = database,
        eventPublisher = OutboxEventPublisher(),
        auditLog = DatabaseAuditLog(),
        meterRegistry = registry,
    )

    embeddedServer(Netty, host = "0.0.0.0", port = config.port, module = {
        module(
            readinessCheck = { withContext(Dispatchers.IO) { DatabaseConfig.isHealthy(dataSource) } },
            meterRegistry = registry,
            identity = config.identity,
            onboarding = onboarding,
        )
    }).start(wait = true)
}

/**
 * Composition of the HTTP application: metrics, kernel plugins (§6.5), bearer-token
 * authentication against the identity provider, health endpoints and the features.
 *
 * @param readinessCheck reports whether required infrastructure (the database)
 * is reachable. `/health/ready` returns 503 while it returns false.
 * @param meterRegistry receives HTTP and JVM metrics; tests pass an in-memory one.
 * @param identity realm to trust; when null every bearer token is rejected (tests of unrelated parts).
 * @param onboarding the user-onboarding feature, or null to run without it.
 */
fun Application.module(
    readinessCheck: suspend () -> Boolean = { true },
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    identity: IdentityProviderSettings? = null,
    onboarding: UserOnboarding? = null,
    jwkProvider: com.auth0.jwk.JwkProvider? = null,
) {
    installMetrics(meterRegistry)
    installWebKernel {
        onboarding?.let { rateLimits(it.rateLimits) }
    }
    install(Authentication) {
        keycloak {
            if (identity != null) settings(identity) else { issuer = "unconfigured"; this.jwkProvider = NoKeysJwkProvider }
            if (jwkProvider != null) this.jwkProvider = jwkProvider
            this.meterRegistry = meterRegistry
        }
    }
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
        onboarding?.routes(this)
    }
}
