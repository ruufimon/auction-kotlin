package com.gavel.app

import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.MoneyPolicy
import java.net.URI
import java.time.Duration

data class DatabaseConfig(val jdbcUrl: String, val user: String?, val password: String?, val maxPoolSize: Int = 10)

/** Runtime configuration. Secrets come only from the environment; nothing secret has a default. */
data class AppConfig(
    val port: Int,
    val database: DatabaseConfig,
    val jwtSecret: String,
    val webOrigins: List<String>,
    val webBaseUrl: String,
    val cookieSecure: Boolean,
    val money: MoneyPolicy,
    val paymentWindow: Duration,
    val paymentWebhookSecret: String,
    val checkoutBaseUrl: String,
    val adminToken: String?,
    val metricsToken: String?,
    val seedDemoData: Boolean,
    val serviceVersion: String,
    /** Logs verification links instead of emailing them. Local development only. */
    val devEmailLinks: Boolean = false,
) {
    init {
        require(!(devEmailLinks && cookieSecure)) { "DEV_EMAIL_LINKS is for local development and cannot be combined with COOKIE_SECURE=true" }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("Environment variable $name is required")
            val currency = CurrencyCode(env["CURRENCY"] ?: "USD")
            val webBaseUrl = env["WEB_BASE_URL"] ?: "http://localhost:3000"
            return AppConfig(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                database = databaseConfig(env),
                jwtSecret = required("JWT_SECRET"),
                webOrigins = (env["WEB_ORIGINS"] ?: webBaseUrl).split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() },
                webBaseUrl = webBaseUrl.trimEnd('/'),
                cookieSecure = env["COOKIE_SECURE"]?.toBooleanStrict() ?: true,
                money = MoneyPolicy(currency, com.gavel.kernel.Money(env["MIN_BID_INCREMENT_MINOR"]?.toLong() ?: 100, currency)),
                paymentWindow = Duration.ofHours(env["PAYMENT_WINDOW_HOURS"]?.toLong() ?: 24),
                paymentWebhookSecret = required("PAYMENT_WEBHOOK_SECRET"),
                checkoutBaseUrl = env["CHECKOUT_BASE_URL"] ?: "$webBaseUrl/sandbox/checkout",
                adminToken = env["ADMIN_TOKEN"]?.takeIf { it.length >= 32 },
                metricsToken = env["METRICS_TOKEN"]?.takeIf { it.length >= 16 },
                seedDemoData = env["SEED_DEMO_DATA"]?.toBooleanStrict() ?: false,
                serviceVersion = env["SERVICE_VERSION"] ?: "dev",
                devEmailLinks = env["DEV_EMAIL_LINKS"]?.toBooleanStrict() ?: false,
            )
        }

        /** Accepts JDBC_DATABASE_URL, or the postgresql:// DATABASE_URL that DigitalOcean injects. */
        fun databaseConfig(env: Map<String, String>): DatabaseConfig {
            env["JDBC_DATABASE_URL"]?.let { return DatabaseConfig(it, env["DATABASE_USER"], env["DATABASE_PASSWORD"]) }
            val raw = env["DATABASE_URL"] ?: error("Set DATABASE_URL (postgresql://user:pass@host:port/db) or JDBC_DATABASE_URL")
            val uri = URI(raw)
            require(uri.scheme == "postgresql" || uri.scheme == "postgres") { "DATABASE_URL must use the postgresql:// scheme" }
            val (user, password) = (uri.rawUserInfo ?: ":").split(':', limit = 2).map { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.let { it[0] to it.getOrNull(1) }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return DatabaseConfig("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user.ifEmpty { null }, password)
        }
    }
}
