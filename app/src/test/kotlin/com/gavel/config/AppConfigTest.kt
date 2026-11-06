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
        "JWT_SECRET" to "x".repeat(AppConfig.MIN_JWT_SECRET_LENGTH),
    )

    @Test
    fun `development falls back to local defaults`() {
        val config = AppConfig.fromEnv(env())

        assertFalse(config.isProduction)
        assertEquals(8080, config.port)
        assertEquals("jdbc:postgresql://localhost:5432/auction_db?sslmode=prefer", config.database.jdbcUrl)
    }

    @Test
    fun `production with all settings succeeds and requires TLS to the database`() {
        val config = AppConfig.fromEnv(env(*productionEnv, "PORT" to "9090", "DB_PORT" to "25060"))

        assertTrue(config.isProduction)
        assertEquals(9090, config.port)
        assertEquals("jdbc:postgresql://db.internal:25060/gavel?sslmode=require", config.database.jdbcUrl)
    }

    @Test
    fun `production lists every missing required variable`() {
        val error = assertThrows<IllegalStateException> {
            AppConfig.fromEnv(env("APP_ENV" to "production", "DB_HOST" to "db.internal"))
        }

        listOf("DB_NAME", "DB_USER", "DB_PASSWORD", "JWT_SECRET").forEach { assertContains(error.message!!, it) }
    }

    @Test
    fun `production rejects a short JWT secret`() {
        val error = assertThrows<IllegalStateException> {
            AppConfig.fromEnv(env(*productionEnv, "JWT_SECRET" to "too-short"))
        }

        assertContains(error.message!!, "JWT_SECRET")
    }

    @Test
    fun `non-numeric port is rejected`() {
        assertThrows<IllegalStateException> { AppConfig.fromEnv(env("PORT" to "eighty")) }
    }
}
