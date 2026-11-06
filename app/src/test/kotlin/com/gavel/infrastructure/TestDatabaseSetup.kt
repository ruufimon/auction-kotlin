package com.gavel.infrastructure

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object TestDatabaseSetup {
    
    fun setupTestDatabase(): Database {
        TestDatabaseContainer.start()
        
        val config = HikariConfig().apply {
            jdbcUrl = TestDatabaseContainer.getJdbcUrl()
            username = TestDatabaseContainer.getUsername()
            password = TestDatabaseContainer.getPassword()
            maximumPoolSize = 5
        }
        
        val dataSource = HikariDataSource(config)
        
        // Run migrations
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(
                "classpath:db/migration/shared",
                "classpath:db/migration/onboarding",
                "classpath:db/migration/catalog",
                "classpath:db/migration/bidding",
                "classpath:db/migration/settlement"
            )
            .baselineOnMigrate(true)
            .load()
        
        flyway.migrate()
        
        return Database.connect(dataSource)
    }
    
    fun cleanupTestDatabase() {
        TestDatabaseContainer.stop()
    }
}
