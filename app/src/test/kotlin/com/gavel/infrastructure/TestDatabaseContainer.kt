package com.gavel.infrastructure

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object TestDatabaseContainer {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("auction_test")
        .withUsername("test_user")
        .withPassword("test_password")

    fun start() {
        if (!container.isRunning) {
            container.start()
        }
    }

    fun stop() {
        if (container.isRunning) {
            container.stop()
        }
    }

    fun getJdbcUrl(): String = container.jdbcUrl
    fun getUsername(): String = container.username
    fun getPassword(): String = container.password

    fun reset() {
        // Optional: truncate tables or reset database state between tests
    }
}
