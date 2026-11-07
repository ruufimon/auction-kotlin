package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuspensionSteps(private val world: World) {
    private var preSuspensionToken: String? = null
    private var auditBefore = 0L
    private var suspensions = 0

    @Given("{string} is ACTIVE, seller-enabled, holds a valid access token and refresh token {string}, has an active listing and an open order as seller")
    fun activeSeller(email: String, refreshToken: String) {
        // Listing and order fixtures arrive with M3/M5; the scenarios that need them are @cross-feature.
        world.ensureAccount(email, "ACTIVE")
        val seller = """{"sellerProfile":{"legalName":"Mina K","storeName":"Finds","contactPhone":"+15035550142","payoutAccount":"acct_1"},
            "originAddress":{"line1":"1 Main","city":"Portland","region":"OR","postalCode":"97209","country":"US"}}"""
        world.putProfile(world.userIdOf(email), seller)
        world.call(HttpMethod.Post, "/api/v1/profile/${world.userIdOf(email)}/enable-seller")
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
        preSuspensionToken = world.currentToken
        auditBefore = TestDatabase.count("SELECT count(*) FROM audit_log")
    }

    @Given("an account in state {string}")
    fun accountInState(state: String) {
        world.ensureAccount("${state.lowercase()}@example.com", state)
    }

    @When("an operator suspends it with reason {string}")
    fun operatorSuspendsWithReason(reason: String) {
        suspend(world.currentEmail!!, reason)
    }

    @When("an operator suspends it")
    fun operatorSuspends() {
        suspend(world.currentEmail!!, "FRAUD_REVIEW")
    }

    @When("an operator suspends {string}")
    fun operatorSuspendsEmail(email: String) {
        suspend(email, "FRAUD_REVIEW")
    }

    private fun suspend(email: String, reason: String) {
        world.operatorCall(HttpMethod.Post, "/internal/accounts/${world.userIdOf(email)}/suspend", """{"reason":"$reason"}""")
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
        suspensions++
    }

    @Then("each suspension writes a {string} event with the reason")
    fun eachSuspensionWritesEvent(eventType: String) {
        assertEquals(suspensions.toLong(), TestDatabase.count("SELECT count(*) FROM event_outbox WHERE event_type = '$eventType'"))
        assertTrue(TestDatabase.count("SELECT count(*) FROM event_outbox WHERE event_type = '$eventType' AND payload::text LIKE '%FRAUD_REVIEW%'") >= 1)
    }

    @Then("the application asks the identity provider to disable the user and end its sessions")
    fun providerDisabled() {
        assertTrue(world.idp.user(world.currentEmail!!).subject in world.idpAdmin.disabled, "provider disable not requested")
    }

    @When("the pre-suspension access token is used to update their profile")
    fun oldTokenUpdates() {
        world.putProfile(world.userIdOf(world.currentEmail!!), """{"displayName":"Still here"}""", token = preSuspensionToken)
    }

    @When("the pre-suspension access token is used to read their profile")
    fun oldTokenReads() {
        world.call(HttpMethod.Get, "/api/v1/profile/${world.userIdOf(world.currentEmail!!)}", token = preSuspensionToken)
    }

    @Given("the Keycloak admin API is unreachable")
    fun adminUnreachable() {
        world.idpAdmin.unreachable = true
    }

    @Then("the application state is {string} and tokens are rejected immediately")
    fun stateAndTokensRejected(state: String) {
        oldTokenReads()
        assertEquals(state, world.lastJson["accountState"]?.jsonPrimitive?.contentOrNull, world.lastBodyText)
        oldTokenUpdates()
        assertEquals(403, world.lastResponse?.status?.value, world.lastBodyText)
        assertEquals("ACCOUNT_SUSPENDED", world.lastJson["code"]?.jsonPrimitive?.contentOrNull)
    }

    @Then("the failed provider call is audited and the account-suspended event waits in the outbox for retry")
    fun providerFailureAudited() {
        assertTrue(TestDatabase.count("SELECT count(*) FROM audit_log WHERE action = 'identity-provider.sync-failed'") >= 1)
        assertTrue(TestDatabase.count("SELECT count(*) FROM event_outbox WHERE event_type = 'user-onboarding.account-suspended' AND published_at IS NULL") >= 1)
    }

    @Then("neither the application account nor the Keycloak user is deleted")
    fun nothingDeleted() {
        val email = world.currentEmail!!
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM users WHERE email = '$email' AND deleted_at IS NULL"))
        assertNotNull(world.idp.userOrNull(email))
    }

    @Then("the audit log keeps every prior entry")
    fun auditKept() {
        assertTrue(TestDatabase.count("SELECT count(*) FROM audit_log") > auditBefore)
    }

    @Given("{string} was suspended while in {string}")
    fun suspendedWhileIn(email: String, state: String) {
        world.ensureAccount(email, state)
        suspend(email, "FRAUD_REVIEW")
    }

    @Given("{string} was suspended while {string}")
    fun suspendedWhile(email: String, state: String) {
        suspendedWhileIn(email, state)
    }

    @When("an operator reactivates {string}")
    fun operatorReactivates(email: String) {
        world.currentEmail = email.trim().lowercase()
        world.operatorCall(HttpMethod.Post, "/internal/accounts/${world.userIdOf(email)}/reactivate")
    }

    @Then("the account state is {string} and the Keycloak user is enabled")
    fun stateAndProviderEnabled(state: String) {
        assertEquals(state, world.lastJson["accountState"]?.jsonPrimitive?.contentOrNull, world.lastBodyText)
        assertTrue(world.idp.user(world.currentEmail!!).subject in world.idpAdmin.enabled, "provider enable not requested")
    }
}
