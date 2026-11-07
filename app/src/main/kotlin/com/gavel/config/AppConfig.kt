package com.gavel.config

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

data class JwtSettings(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtlSeconds: Long,
)

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
    val jwt: JwtSettings,
) {
    val isProduction: Boolean get() = environment == PRODUCTION

    companion object {
        const val PRODUCTION = "production"
        const val MIN_JWT_SECRET_LENGTH = 32

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

            val jwt = JwtSettings(
                secret = value("JWT_SECRET", "dev-secret-key-change-in-production-0000"),
                issuer = getenv("JWT_ISSUER")?.takeIf { it.isNotBlank() } ?: "auction-api",
                audience = getenv("JWT_AUDIENCE")?.takeIf { it.isNotBlank() } ?: "auction-client",
                accessTokenTtlSeconds = int("JWT_ACCESS_TOKEN_TTL", 900).toLong(),
            )

            check(missing.isEmpty()) {
                "Missing required environment variables for APP_ENV=production: ${missing.joinToString()}"
            }
            check(!production || jwt.secret.length >= MIN_JWT_SECRET_LENGTH) {
                "JWT_SECRET must be at least $MIN_JWT_SECRET_LENGTH characters in production"
            }

            return AppConfig(
                environment = environment,
                port = int("PORT", 8080),
                database = database,
                jwt = jwt,
            )
        }
    }
}
