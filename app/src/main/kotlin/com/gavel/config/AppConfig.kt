package com.gavel.config

import com.gavel.kernel.auth.IdentityProviderSettings
import com.gavel.onboarding.config.UserOnboardingSettings

data class DatabaseSettings(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val sslMode: String,
    val maxPoolSize: Int,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$name?sslmode=$sslMode"
}

/**
 * Metrics export over OTLP/HTTP (SigNoz, Grafana, New Relic, ... all accept it).
 * Export is off when [endpoint] is null, which is the local-development default.
 */
data class ObservabilitySettings(
    val serviceName: String,
    val environment: String,
    val endpoint: String?,
    val headers: Map<String, String>,
    val exportIntervalSeconds: Long,
) {
    val enabled: Boolean get() = endpoint != null
    val metricsUrl: String? get() = endpoint?.trimEnd('/')?.plus("/v1/metrics")
}

/**
 * Application configuration read from environment variables.
 *
 * Outside production, missing values fall back to local-development defaults
 * that match docker-compose.dev.yml. With `APP_ENV=production` every secret and
 * connection setting is required, so a misconfigured deploy fails at startup
 * instead of running with development credentials.
 */
data class AppConfig(
    val environment: String,
    val port: Int,
    val database: DatabaseSettings,
    /** Keycloak realm that issues the access tokens the API accepts (USR-005). */
    val identity: IdentityProviderSettings,
    val observability: ObservabilitySettings,
    val onboarding: UserOnboardingSettings,
) {
    val isProduction: Boolean get() = environment == PRODUCTION

    companion object {
        const val PRODUCTION = "production"
        const val DEV_OIDC_ISSUER = "http://localhost:8180/realms/gavel"

        fun fromEnv(getenv: (String) -> String? = System::getenv): AppConfig {
            val environment = getenv("APP_ENV")?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() } ?: "development"
            val production = environment == PRODUCTION
            val missing = mutableListOf<String>()

            fun value(name: String, devDefault: String): String {
                val v = getenv(name)?.takeIf { it.isNotBlank() }
                if (v != null) return v
                if (production) missing += name
                return devDefault
            }

            fun int(name: String, default: Int): Int {
                val raw = getenv(name)?.takeIf { it.isNotBlank() } ?: return default
                return raw.toIntOrNull() ?: throw IllegalStateException("$name must be an integer, got '$raw'")
            }

            val database = DatabaseSettings(
                host = value("DB_HOST", "localhost"),
                port = int("DB_PORT", 5432),
                name = value("DB_NAME", "auction_db"),
                user = value("DB_USER", "auction"),
                password = value("DB_PASSWORD", "dev-password"),
                sslMode = getenv("DB_SSLMODE")?.takeIf { it.isNotBlank() } ?: if (production) "require" else "prefer",
                maxPoolSize = int("DB_POOL_SIZE", 10),
            )

            // The realm URL doubles as the token issuer; JWKS defaults to the realm's certs endpoint.
            val issuer = value("OIDC_ISSUER", DEV_OIDC_ISSUER).trimEnd('/')
            val identity = IdentityProviderSettings(
                issuer = issuer,
                audience = getenv("OIDC_AUDIENCE")?.takeIf { it.isNotBlank() } ?: IdentityProviderSettings.DEFAULT_AUDIENCE,
                jwksUrl = getenv("OIDC_JWKS_URL")?.takeIf { it.isNotBlank() } ?: "$issuer/protocol/openid-connect/certs",
            )

            // Standard OpenTelemetry variable names so any OTLP backend's docs apply.
            val observability = ObservabilitySettings(
                serviceName = getenv("OTEL_SERVICE_NAME")?.takeIf { it.isNotBlank() } ?: "gavel-api",
                environment = environment,
                endpoint = getenv("OTEL_EXPORTER_OTLP_ENDPOINT")?.takeIf { it.isNotBlank() },
                headers = parseOtlpHeaders(getenv("OTEL_EXPORTER_OTLP_HEADERS")),
                exportIntervalSeconds = int("OTEL_METRIC_EXPORT_INTERVAL", 30).toLong(),
            )

            check(missing.isEmpty()) {
                "Missing required environment variables for APP_ENV=production: ${missing.joinToString()}"
            }
            check(!production || identity.issuer.startsWith("https://")) {
                "OIDC_ISSUER must be an https URL in production"
            }

            return AppConfig(
                environment = environment,
                port = int("PORT", 8080),
                database = database,
                identity = identity,
                observability = observability,
                onboarding = UserOnboardingSettings.fromEnv(getenv, production),
            )
        }

        /** `OTEL_EXPORTER_OTLP_HEADERS` is `key=value,key2=value2`; values may contain `=` (e.g. base64). */
        internal fun parseOtlpHeaders(raw: String?): Map<String, String> =
            raw.orEmpty().split(',')
                .map { it.trim() }
                .filter { it.contains('=') }
                .associate { pair ->
                    val i = pair.indexOf('=')
                    pair.substring(0, i).trim() to pair.substring(i + 1).trim()
                }
    }
}
