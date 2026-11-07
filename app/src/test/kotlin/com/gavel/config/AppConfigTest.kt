package com.gavel.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private val productionEnv = arrayOf(
        "APP_ENV" to "production",
        "DB_HOST" to "db.internal",
        "DB_NAME" to "gavel",
        "DB_USER" to "gavel_app",
        "DB_PASSWORD" to "s3cret",
        "OIDC_ISSUER" to "https://id.gavel.example/realms/gavel",
        "ASSESSMENT_ENCRYPTION_KEY" to "ZGV2LW9ubHktYXNzZXNzbWVudC1rZXktMDAwMDAwMDA=",
        "INTERNAL_API_TOKEN" to "operator-secret",
    )

    @Test
    fun `development falls back to local defaults`() {
        val config = AppConfig.fromEnv(env())

        assertFalse(config.isProduction)
        assertEquals(8080, config.port)
        assertEquals("jdbc:postgresql://localhost:5432/auction_db?sslmode=prefer", config.database.jdbcUrl)
        assertEquals(AppConfig.DEV_OIDC_ISSUER, config.identity.issuer)
        assertEquals("${AppConfig.DEV_OIDC_ISSUER}/protocol/openid-connect/certs", config.identity.jwksUrl)
        assertEquals("gavel-api", config.identity.audience)
        assertEquals(null, config.onboarding.internalApiToken)
    }

    @Test
    fun `production with all settings succeeds and requires TLS to the database`() {
        val config = AppConfig.fromEnv(env(*productionEnv, "PORT" to "9090", "DB_PORT" to "25060"))

        assertTrue(config.isProduction)
        assertEquals(9090, config.port)
        assertEquals("jdbc:postgresql://db.internal:25060/gavel?sslmode=require", config.database.jdbcUrl)
        assertEquals("https://id.gavel.example/realms/gavel/protocol/openid-connect/certs", config.identity.jwksUrl)
    }

    @Test
    fun `production lists every missing required variable`() {
        val error = assertThrows<IllegalStateException> {
            AppConfig.fromEnv(env("APP_ENV" to "production", "DB_HOST" to "db.internal"))
        }

        listOf("DB_NAME", "DB_USER", "DB_PASSWORD", "OIDC_ISSUER").forEach { assertContains(error.message!!, it) }
    }

    @Test
    fun `production requires the onboarding secrets`() {
        val error = assertThrows<IllegalStateException> {
            AppConfig.fromEnv(env(*productionEnv.filterNot { it.first == "ASSESSMENT_ENCRYPTION_KEY" || it.first == "INTERNAL_API_TOKEN" }.toTypedArray()))
        }

        assertContains(error.message!!, "ASSESSMENT_ENCRYPTION_KEY")
        assertContains(error.message!!, "INTERNAL_API_TOKEN")
    }

    @Test
    fun `production rejects a plain-http issuer`() {
        val error = assertThrows<IllegalStateException> {
            AppConfig.fromEnv(env(*productionEnv, "OIDC_ISSUER" to "http://id.gavel.example/realms/gavel"))
        }

        assertContains(error.message!!, "OIDC_ISSUER")
    }

    @Test
    fun `non-numeric port is rejected`() {
        assertThrows<IllegalStateException> { AppConfig.fromEnv(env("PORT" to "eighty")) }
    }
}
