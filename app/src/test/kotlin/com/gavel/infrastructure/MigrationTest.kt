package com.gavel.infrastructure

import com.gavel.config.DatabaseSettings
import com.gavel.migration.FlywayConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Applies every feature's migrations to a real PostgreSQL 16 and checks the
 * resulting schema. Skipped (not passed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("auction_test")
            .withUsername("test_user")
            .withPassword("test_password")

        private val EXPECTED_TABLES = setOf(
            "event_outbox", "audit_log",
            "users",
            "listings", "listing_images",
            "bids",
            "orders",
        )
    }

    private lateinit var dataSource: HikariDataSource

    @BeforeAll
    fun setUp() {
        dataSource = DatabaseConfig.createDataSource(
            DatabaseSettings(
                host = postgres.host,
                port = postgres.firstMappedPort,
                name = postgres.databaseName,
                user = postgres.username,
                password = postgres.password,
                sslMode = "disable",
                maxPoolSize = 2,
            )
        )
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `all feature migrations apply and create the expected tables`() {
        val first = FlywayConfig.runMigrations(dataSource)
        assertEquals(11, first.migrationsExecuted, "every migration file should be picked up")

        val tables = dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
            }
        }
        assertTrue(tables.containsAll(EXPECTED_TABLES), "missing tables: ${EXPECTED_TABLES - tables}")
        // Credentials and tokens live in Keycloak (USR-002, USR-006): the M1 tables are gone.
        assertTrue(tables.none { it.contains("token") }, "no token tables expected, found: ${tables.filter { it.contains("token") }}")

        val second = FlywayConfig.runMigrations(dataSource)
        assertEquals(0, second.migrationsExecuted, "re-running migrations must be a no-op")
    }

    @Test
    fun `Exposed connects through the pooled data source`() {
        val db = Database.connect(dataSource)
        val one = transaction(db) { exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) } }
        assertEquals(1, one)
    }

    @Test
    fun `readiness check reports a reachable database as healthy`() {
        assertTrue(DatabaseConfig.isHealthy(dataSource))
    }
}
