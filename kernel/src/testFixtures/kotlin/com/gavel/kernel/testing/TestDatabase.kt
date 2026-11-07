package com.gavel.kernel.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One PostgreSQL 16 container per test JVM, migrated with the given Flyway
 * locations. Feature modules pass the shared folder plus their own.
 */
object TestDatabase {
    val dockerAvailable: Boolean by lazy { runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) }

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("gavel_test")
            .withUsername("test_user")
            .withPassword("test_password")
            .also { it.start() }
    }

    val dataSource: HikariDataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 8
                poolName = "gavel-test"
            },
        )
    }

    val database: Database by lazy { Database.connect(dataSource) }

    private val migrated = mutableSetOf<String>()

    /** Idempotent per location set. */
    @Synchronized
    fun migrate(vararg locations: String): Database {
        val key = locations.sorted().joinToString()
        if (migrated.add(key)) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations(*locations)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        }
        return database
    }

    fun truncate(vararg tables: String) {
        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("TRUNCATE TABLE ${tables.joinToString()} CASCADE") }
        }
    }

    fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(sql).use(read) } }

    fun count(sql: String): Long = query(sql) { rs -> rs.next(); rs.getLong(1) }
}
