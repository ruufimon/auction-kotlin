package com.gavel.migration

import org.flywaydb.core.Flyway
import javax.sql.DataSource

object FlywayConfig {
    
    fun runMigrations(dataSource: DataSource) {
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
        
        val result = flyway.migrate()
        println("Flyway migrated ${result.migrationsExecuted} migrations")
    }
}
