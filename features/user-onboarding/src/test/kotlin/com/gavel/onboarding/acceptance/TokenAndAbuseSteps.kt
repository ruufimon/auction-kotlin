package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TokenAndAbuseSteps(private val world: World) {

    @Given("a Keycloak realm created from deploy\\/keycloak\\/realm-gavel.json with {string} on and max reuse {int}")
    fun realmWithRotation(setting: String, maxReuse: Int) {
        // Realm configuration (USR-006) is asserted by @provider scenarios.
    }

    @Given("{string} is ACTIVE and signed in, holding refresh token {string}")
    fun activeHoldingRefreshToken(email: String, refreshToken: String) {
        world.ensureAccount(email, "ACTIVE")
    }

    @Then("the application database has no table or column containing refresh tokens")
    fun noRefreshTokenStorage() {
        val columns = TestDatabase.count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND (column_name ILIKE '%refresh%' OR column_name ILIKE '%token%')")
        val tables = TestDatabase.count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND (table_name ILIKE '%refresh%' OR table_name ILIKE '%token%')")
        assertEquals(0L, columns + tables)
    }

    @Then("the application logs contain no refresh-token values")
    fun logsHaveNoRefreshTokens() {
        val leaks = world.logLines().filter { Regex("refresh[_-]?token\\s*[=:]", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        assertTrue(leaks.isEmpty(), "refresh token material in logs: $leaks")
    }

    @Given("the access token is {int} minutes old")
    fun accessTokenAge(minutes: Int) {
        val user = world.idp.user(world.currentEmail!!)
        world.currentToken = world.idp.mint(
            subject = user.subject, email = user.email, emailVerified = user.emailVerified,
            issuedAt = world.clock.instant().minus(Duration.ofMinutes(minutes.toLong())), ttl = Duration.ofMinutes(15),
        )
    }

    @When("{int} GET \\/api\\/v1\\/me calls are made from one client within one minute")
    fun manyBootstrapCalls(count: Int) {
        world.idp.registerPasswordUser("flood@example.com")
        world.signInAs("flood@example.com")
        repeat(count) { world.call(HttpMethod.Get, "/api/v1/me") }
    }

    @Then("the next call returns status {int}")
    fun nextCallStatus(status: Int) {
        world.call(HttpMethod.Get, "/api/v1/me")
        assertEquals(status, world.lastResponse?.status?.value, world.lastBodyText)
    }

    @Then("a {string} header is present")
    fun headerPresent(name: String) {
        assertNotNull(world.lastResponse?.headers?.get(name), "missing $name header")
    }
}
