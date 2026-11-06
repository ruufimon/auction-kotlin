package com.gavel.app

import com.gavel.kernel.Transactor
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import javax.sql.DataSource

/**
 * Each module owns one PostgreSQL schema and its own Flyway history. Migrations
 * run in this fixed order so cross-schema references always resolve.
 */
val MODULE_SCHEMAS = listOf("platform", "onboarding", "catalog", "bidding", "settlement")

fun dataSource(config: DatabaseConfig): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        config.user?.let { username = it }
        config.password?.let { password = it }
        maximumPoolSize = config.maxPoolSize
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        poolName = "gavel"
        initializationFailTimeout = -1
    },
)

fun migrate(dataSource: DataSource) {
    for (schema in MODULE_SCHEMAS) {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/migration/$schema")
            .load()
            .migrate()
    }
}

class ExposedTransactor(private val database: Database) : Transactor {
    override suspend fun <T> inTransaction(block: () -> T): T =
        withContext(Dispatchers.IO + MDCContext()) { blocking(block) }

    override fun <T> blocking(block: () -> T): T =
        transaction(database, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) { block() }
}

fun connect(dataSource: DataSource): Database = Database.connect(dataSource)

fun isDatabaseReachable(dataSource: DataSource): Boolean =
    runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false)
