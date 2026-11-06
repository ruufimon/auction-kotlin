package com.gavel.infrastructure

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import com.gavel.migration.FlywayConfig

object DatabaseConfig {
    
    fun initializeDatabase(): Database {
        val dbHost = System.getenv("DB_HOST") ?: "localhost"
        val dbPort = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432
        val dbName = System.getenv("DB_NAME") ?: "auction_db"
        val dbUser = System.getenv("DB_USER") ?: "auction"
        val dbPassword = System.getenv("DB_PASSWORD") ?: "dev-password"
        
        val jdbcUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
        
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = dbUser
            password = dbPassword
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
        }
        
        val dataSource = HikariDataSource(config)
        
        // Run Flyway migrations
        FlywayConfig.runMigrations(dataSource)
        
        return Database.connect(dataSource).also {
            println("Database connected: $jdbcUrl")
        }
    }
}
