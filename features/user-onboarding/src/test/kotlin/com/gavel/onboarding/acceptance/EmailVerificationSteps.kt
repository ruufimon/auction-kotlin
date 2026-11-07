package com.gavel.onboarding.acceptance

import com.auth0.jwt.JWT
import com.gavel.kernel.testing.TestDatabase
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpMethod
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailVerificationSteps(private val world: World) {

    @Given("{string} registered with a password at {string} and received a verification email")
    fun registeredAt(email: String, at: String) {
        world.clock.set(Instant.parse(at))
        world.idp.registerPasswordUser(email)
        world.signInAs(email)
        world.me()
        assertEquals("PENDING_VERIFICATION", world.lastJson["accountState"]?.let { it.toString().trim('"') })
    }

    @When("the verification link is opened at {string}")
    fun linkOpenedAt(at: String) {
        world.clock.set(Instant.parse(at))
        world.idp.markEmailVerified(world.currentEmail!!)
    }

    @When("the user signs in again and the web app calls GET \\/api\\/v1\\/me")
    fun signsInAgainAndBootstraps() {
        world.signInAs(world.currentEmail!!)
        world.call(HttpMethod.Get, "/api/v1/me")
    }

    @Then("the token claim {string} is {boolean}")
    fun tokenClaimIs(claim: String, expected: Boolean) {
        assertEquals(expected, JWT.decode(world.currentToken).getClaim(claim).asBoolean())
    }

    @Given("the email has been verified")
    @When("the email is verified and the account transitions")
    fun emailVerifiedAndSynced() {
        world.idp.markEmailVerified(world.currentEmail!!)
        world.signInAs(world.currentEmail!!)
        world.me()
    }

    @Then("the audit log holds an entry with actor = the user, action = {string} and the aggregate id")
    fun auditEntry(action: String) {
        val count = TestDatabase.count(
            """SELECT count(*) FROM audit_log a JOIN users u ON u.id = a.aggregate_id
               WHERE a.action = '$action' AND a.actor_id = u.id AND u.email = '${world.currentEmail}'""",
        )
        assertTrue(count >= 1, "no audit entry $action for ${world.currentEmail}")
    }
}
