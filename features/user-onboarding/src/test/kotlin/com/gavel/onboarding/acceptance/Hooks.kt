package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.ParameterType
import org.junit.jupiter.api.Assumptions.assumeTrue

class Hooks(private val world: World) {
    @Before
    fun start() {
        assumeTrue(TestDatabase.dockerAvailable, "Docker is required for acceptance scenarios")
        world.start()
    }

    @After
    fun stop() {
        if (world.app != null) world.stop()
    }

    @ParameterType("true|false")
    fun boolean(value: String): Boolean = value.toBoolean()
}
