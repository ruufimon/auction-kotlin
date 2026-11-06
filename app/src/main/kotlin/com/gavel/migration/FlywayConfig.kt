package com.gavel.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object FlywayConfig {
    private val log = LoggerFactory.getLogger(FlywayConfig::class.java)

    /** Migration folders, one per owning module. See AGENT.md "Database Migrations". */
    val LOCATIONS = arrayOf(
        "classpath:db/migration/shared",
        "classpath:db/migration/onboarding",
        "classpath:db/migration/catalog",
        "classpath:db/migration/bidding",
        "classpath:db/migration/settlement",
    )

    fun runMigrations(dataSource: DataSource): MigrateResult {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(*LOCATIONS)
            // Fail on misnamed files instead of silently skipping them.
            .validateMigrationNaming(true)
            .load()
            .migrate()
        log.info("Flyway applied {} migration(s); schema version {}", result.migrationsExecuted, result.targetSchemaVersion)
        return result
    }
}
