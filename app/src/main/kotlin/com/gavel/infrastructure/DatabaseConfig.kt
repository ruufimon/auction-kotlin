package com.gavel.infrastructure

import com.gavel.config.DatabaseSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object DatabaseConfig {

    fun createDataSource(settings: DatabaseSettings): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = settings.jdbcUrl
            username = settings.user
            password = settings.password
            maximumPoolSize = settings.maxPoolSize
            minimumIdle = minOf(2, settings.maxPoolSize)
            connectionTimeout = 30_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            poolName = "gavel-db"
        }
        return HikariDataSource(config)
    }

    /** True when a connection can be borrowed and validated within [timeoutSeconds]. */
    fun isHealthy(dataSource: DataSource, timeoutSeconds: Int = 2): Boolean =
        try {
            dataSource.connection.use { it.isValid(timeoutSeconds) }
        } catch (e: Exception) {
            false
        }
}
