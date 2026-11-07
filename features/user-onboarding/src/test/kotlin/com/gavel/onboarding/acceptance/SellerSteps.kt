package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import com.gavel.onboarding.domain.SellerRequirements
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SellerSteps(private val world: World) {
    private val statuses = mutableListOf<Int>()

    @Given("the configured required seller fields are {}")
    fun requiredSellerFields(list: String) {
        world.sellerRequirements = world.sellerRequirements.copy(sellerProfileFields = list.split(",").map { it.trim() })
    }

    @Given("the configured required origin-address fields are {}")
    fun requiredOriginFields(list: String) {
        world.sellerRequirements = world.sellerRequirements.copy(originAddressFields = list.split(",").map { it.trim() })
    }

    private fun sellerBody(requirements: SellerRequirements = world.sellerRequirements, exceptSeller: String? = null, exceptOrigin: String? = null): String {
        val seller = requirements.sellerProfileFields.filter { it != exceptSeller }.joinToString(",") { "\"$it\":\"${if (it == "storeName") "Mina's Finds" else "value-$it"}\"" }
        val origin = requirements.originAddressFields.filter { it != exceptOrigin }.joinToString(",") { "\"$it\":\"${if (it == "country") "US" else "value-$it"}\"" }
        return """{"sellerProfile":{$seller},"originAddress":{$origin}}"""
    }

    private fun fill(email: String, body: String) {
        world.putProfile(world.userIdOf(email), body, token = world.tokensByEmail[email])
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
    }

    @Given("{string} is ACTIVE with all required seller and origin-address fields filled")
    fun activeComplete(email: String) {
        world.ensureAccount(email, "ACTIVE")
        fill(email, sellerBody())
    }

    @Given("{string} is ACTIVE with every required field except {word}")
    fun activeMissingSellerField(email: String, field: String) {
        world.ensureAccount(email, "ACTIVE")
        fill(email, sellerBody(exceptSeller = field))
    }

    @Given("{string} is ACTIVE with a complete seller profile and no origin {word}")
    fun activeMissingOriginField(email: String, field: String) {
        world.ensureAccount(email, "ACTIVE")
        fill(email, sellerBody(exceptOrigin = field))
    }

    @Given("{string} is in state {string} with a complete seller profile")
    fun inStateComplete(email: String, state: String) {
        if (state == "SUSPENDED") {
            world.ensureAccount(email, "ACTIVE")
            fill(email, sellerBody())
            world.operatorCall(HttpMethod.Post, "/internal/accounts/${world.userIdOf(email)}/suspend", """{"reason":"FRAUD_REVIEW"}""")
            assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
        } else {
            world.ensureAccount(email, state)
            fill(email, sellerBody())
        }
    }

    @Given("{string} already has {string} true")
    fun alreadySeller(email: String, field: String) {
        activeComplete(email)
        enableSeller()
        assertEquals(true, world.lastJson[field]?.jsonPrimitive?.booleanOrNull, world.lastBodyText)
    }

    @Given("{string} and {string} are both ACTIVE")
    fun bothActive(a: String, b: String) {
        world.ensureAccount(a, "ACTIVE")
        world.ensureAccount(b, "ACTIVE")
    }

    @Given("the deployment adds {string} to the required seller fields")
    fun deploymentAddsField(field: String) {
        world.sellerRequirements = world.sellerRequirements.copy(sellerProfileFields = world.sellerRequirements.sellerProfileFields + field)
    }

    @Given("{string} is ACTIVE with every previously required field")
    fun activeWithPreviousFields(email: String) {
        world.ensureAccount(email, "ACTIVE")
        fill(email, sellerBody(SellerRequirements.DEFAULT))
    }

    @Given("{string} enabled selling")
    fun enabledSelling(email: String) {
        activeComplete(email)
        enableSeller()
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
    }

    @When("they call enable-seller on their own profile")
    @When("they call enable-seller")
    @When("they call enable-seller again")
    fun enableSeller() {
        val email = world.currentEmail!!
        world.call(HttpMethod.Post, "/api/v1/profile/${world.userIdOf(email)}/enable-seller", token = world.tokensByEmail[email])
    }

    @When("{string} calls enable-seller on {string}'s profile")
    fun enableSellerOnOther(actor: String, target: String) {
        world.call(HttpMethod.Post, "/api/v1/profile/${world.userIdOf(target)}/enable-seller", token = world.tokensByEmail[actor.trim().lowercase()])
    }

    @Then("the profile has {string} true and a {string} timestamp")
    fun profileHasSellerFlag(flag: String, timestamp: String) {
        assertEquals(true, world.lastJson[flag]?.jsonPrimitive?.booleanOrNull, world.lastBodyText)
        assertNotNull(world.lastJson[timestamp]?.jsonPrimitive?.contentOrNull)
    }

    @Then("the user id is unchanged, no second account exists")
    fun sameAccount() {
        val email = world.currentEmail!!
        assertEquals(world.userIdOf(email), world.lastJson["userId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM users WHERE email = '$email'"))
    }

    @Then("no second event is written")
    fun noSecondEvent() {
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM event_outbox WHERE event_type = 'user-onboarding.seller-enabled'"))
    }

    @When("a visitor registers, verifies their email, passes the credit-eligibility assessment, completes the seller profile and origin address, and calls enable-seller")
    fun endToEnd() {
        val email = "newseller@example.com"
        world.idp.registerPasswordUser(email, displayName = "New Seller")
        world.signInAs(email)
        world.call(HttpMethod.Get, "/api/v1/me").also { statuses += it.status.value }
        world.userIdByEmail[email] = world.lastJson["userId"]!!.jsonPrimitive.content
        world.idp.markEmailVerified(email)
        world.signInAs(email)
        world.call(HttpMethod.Get, "/api/v1/me").also { statuses += it.status.value }
        world.putProfile(world.userIdOf(email), """{"assessment":{"age":34,"annualIncome":6800000,"netWealth":1500000}}""").also { statuses += it.status.value }
        world.putProfile(world.userIdOf(email), sellerBody()).also { statuses += it.status.value }
        enableSeller().also { statuses += world.lastResponse!!.status.value }
    }

    @Then("each step succeeds in order")
    fun eachStepSucceeds() {
        assertTrue(statuses.all { it == 200 }, "statuses were $statuses; last body ${world.lastBodyText}")
    }

    @Then("the final profile shows accountState {string} and sellerEnabled true")
    fun finalProfile(state: String) {
        assertEquals(state, world.lastJson["accountState"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, world.lastJson["sellerEnabled"]?.jsonPrimitive?.booleanOrNull)
    }

    @Then("the outbox holds, in order, {}")
    fun outboxOrder(list: String) {
        val expected = list.split(",").map { "user-onboarding." + it.trim() }
        val actual = TestDatabase.query("SELECT event_type FROM event_outbox ORDER BY created_at") { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }
        assertEquals(expected, actual)
    }
}
