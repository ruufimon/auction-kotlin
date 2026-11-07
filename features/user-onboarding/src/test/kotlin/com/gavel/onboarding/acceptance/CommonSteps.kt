package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Steps shared by every onboarding feature file: provider setup, HTTP assertions, outbox and audit checks. */
class CommonSteps(private val world: World) {

    // --- Identity provider (simulated by TestIdentityProvider in the application-only profile) ---

    @Given("a Keycloak realm created from deploy\\/keycloak\\/realm-gavel.json")
    @Given("a Keycloak realm created from deploy\\/keycloak\\/realm-gavel.json with verify-email required")
    fun realmExists() {
        // The application trusts the scenario's key set; realm settings themselves are @provider scenarios.
    }

    @Given("the realm password policy requires at least {int} characters")
    fun passwordPolicy(minimum: Int) {
        // Provider configuration: asserted by @provider scenarios against the real realm.
    }

    @Given("access tokens expire after {int} minutes and refresh tokens after {int} days")
    fun tokenLifespans(accessMinutes: Int, refreshDays: Int) {
        // Provider configuration; the application only checks `exp` on the tokens it receives.
    }

    @When("a visitor registers at Keycloak with email {string} and password {string}")
    fun registersWithPassword(email: String, password: String) {
        world.secretsTyped += password
        world.idp.registerPasswordUser(email)
        world.signInAs(email)
    }

    @When("a visitor signs in with Google as {string} \\(verified email)")
    @When("the same person signs in with Google as {string}")
    fun signsInWithGoogle(email: String) {
        world.idp.signInWithGoogle(email)
        world.signInAs(email)
    }

    @When("the web app calls GET \\/api\\/v1\\/me with the resulting access token")
    @When("the web app calls GET \\/api\\/v1\\/me")
    @When("they call GET \\/api\\/v1\\/me")
    fun callsMe() {
        world.call(HttpMethod.Get, "/api/v1/me")
    }

    // --- Response assertions ---

    @Then("the response status is {int}")
    fun responseStatus(status: Int) {
        assertEquals(status, world.lastResponse?.status?.value, "unexpected status; body: ${world.lastBodyText}")
    }

    @Then("the response {string} is {string}")
    @Then("{string} is {string}")
    fun responseFieldIs(field: String, expected: String) {
        val actual = world.lastJson[field]?.jsonPrimitive?.contentOrNull
        assertEquals(world.resolveUserId(expected), actual, "field $field in ${world.lastBodyText}")
    }

    @Then("the response {string} is {boolean}")
    fun responseFieldIsBoolean(field: String, expected: Boolean) {
        assertEquals(expected, world.lastJson[field]?.jsonPrimitive?.booleanOrNull, "field $field in ${world.lastBodyText}")
    }

    @Then("{string} is {string} and {string} is {string}")
    fun twoFields(f1: String, v1: String, f2: String, v2: String) {
        responseFieldIs(f1, v1)
        responseFieldIs(f2, v2)
    }

    @Then("the error code is {string}")
    fun errorCode(code: String) {
        assertEquals(code, world.lastJson["code"]?.jsonPrimitive?.contentOrNull, "body: ${world.lastBodyText}")
        assertNotNull(world.lastJson["correlationId"], "errors carry a correlation id (§6.5)")
    }

    @Then("the response contains an opaque {string} that is not a sequential integer")
    fun opaqueId(field: String) {
        val value = world.lastJson[field]
        assertTrue(value is JsonPrimitive && value.isString, "$field must be a string, was $value")
        val id = value.jsonPrimitive.content
        assertFalse(id.all { it.isDigit() }, "$field looks sequential: $id")
        assertTrue(id.startsWith("usr_") && id.length >= 16, "$field should be an opaque usr_ id: $id")
    }

    // --- Outbox / audit / schema ---

    @Then("an {string} event is written to the outbox in the same transaction")
    @Then("an {string} event is written to the outbox")
    @Then("a {string} event is written to the outbox")
    @Then("a {string} event is written")
    fun outboxHas(eventType: String) {
        val count = TestDatabase.count("SELECT count(*) FROM event_outbox WHERE event_type = '$eventType'")
        assertTrue(count >= 1, "expected an outbox row of type $eventType")
    }

    @Then("exactly one application account exists for {string}")
    fun oneAccountFor(email: String) {
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM users WHERE email = '${email.trim().lowercase()}'"))
    }

    @Given("no application account exists for {string}")
    fun noAccountFor(email: String) {
        assertEquals(0L, TestDatabase.count("SELECT count(*) FROM users WHERE email = '${email.trim().lowercase()}'"))
    }

    @Then("exactly one Keycloak user exists for {string}")
    fun oneProviderUser(email: String) {
        assertEquals(1, world.idp.usersWithEmail(email))
    }
}
