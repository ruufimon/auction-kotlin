package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationSteps(private val world: World) {

    @Given("{string} registered with a password and verified their email, with userId {string}")
    fun registeredAndVerified(email: String, label: String) {
        world.idp.registerPasswordUser(email)
        world.signInAs(email)
        world.call(HttpMethod.Get, "/api/v1/me")
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
        world.userIdAliases[label] = world.lastJson["userId"]!!.jsonPrimitive.content
        world.idp.markEmailVerified(email)
        world.signInAs(email)
        world.call(HttpMethod.Get, "/api/v1/me")
        assertEquals("PENDING_CREDIT_ELIGIBILITY", world.lastJson["accountState"]?.jsonPrimitive?.contentOrNull)
    }

    @Given("a fresh Keycloak user with no application account")
    fun freshProviderUser() {
        world.idp.registerPasswordUser("fresh@example.com")
        world.signInAs("fresh@example.com")
    }

    @When("two GET \\/api\\/v1\\/me calls arrive at the same time with that user's token")
    fun concurrentBootstrap() = runBlocking {
        val token = world.currentToken!!
        val bodies = coroutineScope {
            (1..2).map {
                async {
                    val r = world.client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $token") }
                    r.status.value to r.bodyAsText()
                }
            }.awaitAll()
        }
        concurrentResults = bodies
    }

    private var concurrentResults: List<Pair<Int, String>> = emptyList()

    @Then("both return {int} with the same {string}")
    fun bothReturn(status: Int, field: String) {
        assertEquals(2, concurrentResults.size)
        concurrentResults.forEach { (s, body) -> assertEquals(status, s, body) }
        val values = concurrentResults.map { Json.parseToJsonElement(it.second).jsonObject[field]?.jsonPrimitive?.content }.toSet()
        assertEquals(1, values.size, "different $field values: $values")
    }

    @Then("exactly one account row exists for the subject")
    fun oneRowForSubject() {
        val subject = world.idp.user(world.currentEmail!!).subject
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM users WHERE idp_subject = '$subject'"))
    }

    @Then("the application schema has no password column and no refresh-token table")
    fun noCredentialSchema() {
        val passwordColumns = TestDatabase.count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND column_name ILIKE '%password%'",
        )
        assertEquals(0L, passwordColumns, "no table may store passwords (USR-002)")
        val tokenTables = TestDatabase.count(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND (table_name ILIKE '%refresh%' OR table_name ILIKE '%token%')",
        )
        assertEquals(0L, tokenTables, "the application holds no refresh or verification tokens (USR-006)")
    }

    @Then("the application logs for a registration contain no password value")
    fun logsHaveNoPassword() {
        world.secretsTyped += "correct-horse-battery"
        registersAndBootstraps()
        val offending = world.logLines().filter { line -> world.secretsTyped.any { line.contains(it) } }
        assertTrue(offending.isEmpty(), "password values leaked into logs: $offending")
    }

    @When("a visitor registers and bootstraps")
    fun registersAndBootstraps() {
        world.idp.registerPasswordUser("visitor@example.com", displayName = "Visitor")
        world.signInAs("visitor@example.com")
        world.call(HttpMethod.Get, "/api/v1/me")
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
    }

    @Then("no role or account-type choice was offered")
    fun noRoleChoice() {
        // Bootstrap is a GET with no body: the client cannot send a role, and the account carries none.
        assertNull(world.lastJson["role"]?.jsonPrimitive?.contentOrNull)
        assertNull(world.lastJson["accountType"]?.jsonPrimitive?.contentOrNull)
    }

    @Then("the created account has {string} false")
    fun accountFieldFalse(field: String) {
        assertFalse(world.lastJson[field]?.jsonPrimitive?.booleanOrNull ?: true, "$field should be false in ${world.lastBodyText}")
    }
}
