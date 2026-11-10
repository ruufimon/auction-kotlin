package com.gavel.onboarding.acceptance

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileSteps(private val world: World) {
    private val snapshots = mutableMapOf<String, JsonObject>()
    private var expected = mapOf<String, String>()
    private var versionBefore = 0

    @Given("{string} is ACTIVE and signed in with user id {string}")
    @Given("{string} is ACTIVE with user id {string}")
    fun activeWithLabel(email: String, label: String) {
        world.ensureAccount(email, "ACTIVE", label)
        snapshots[label] = readAs(label, label)
    }

    private fun readAs(actor: String, target: String): JsonObject {
        world.call(HttpMethod.Get, "/api/v1/profile/${world.resolveUserId(target)}", token = world.tokenForAlias(actor))
        return world.lastJson
    }

    @When("{string} reads profile {string}")
    fun readsProfile(actor: String, target: String) {
        readAs(actor, target)
    }

    @Then("the response is indistinguishable from reading another user's profile")
    fun indistinguishable() {
        val unknown = world.lastResponse!!.status to world.lastJson.minus("correlationId")
        readAs("usr_mina", "usr_sam")
        val other = world.lastResponse!!.status to world.lastJson.minus("correlationId")
        assertEquals(other, unknown)
    }

    private fun update(actor: String, target: String, body: String) {
        versionBefore = readAs(actor, actor)["version"]?.jsonPrimitive?.intOrNull ?: 0
        world.putProfile(world.resolveUserId(target), body, token = world.tokenForAlias(actor))
    }

    @When("{string} updates their profile with display name {string} and phone {string}")
    fun updatesNameAndPhone(actor: String, name: String, phone: String) {
        expected = mapOf("displayName" to name, "phone" to phone)
        update(actor, actor, """{"displayName":"$name","phone":"$phone"}""")
    }

    @Then("the response reflects the new values and a new {string}")
    fun reflectsNewValues(versionField: String) {
        expected.forEach { (k, v) -> assertEquals(v, world.lastJson[k]?.jsonPrimitive?.contentOrNull, world.lastBodyText) }
        val version = assertNotNull(world.lastJson[versionField]?.jsonPrimitive?.intOrNull)
        assertTrue(version > versionBefore, "version should advance past $versionBefore, was $version")
    }

    @When("{string} updates their shipping address to {string}, {string}, {string}, {string}, {string}")
    fun updatesAddress(actor: String, line1: String, city: String, region: String, postalCode: String, country: String) {
        expected = mapOf("line1" to line1, "city" to city, "region" to region, "postalCode" to postalCode, "country" to country)
        update(actor, actor, """{"shippingAddress":{"line1":"$line1","city":"$city","region":"$region","postalCode":"$postalCode","country":"$country"}}""")
    }

    @Given("{string} is called {string} at the identity provider")
    fun calledAtProvider(email: String, name: String) {
        world.idp.renameAtProvider(email, name)
        // The next request carries a fresh token with the new name claim.
        world.tokensByEmail.remove(email.trim().lowercase())
    }

    @Given("{string} makes an authenticated request")
    @When("{string} makes another authenticated request")
    fun authenticatedRequest(actor: String) {
        world.call(HttpMethod.Get, "/api/v1/me", token = world.tokenForAlias(actor))
        assertEquals(200, world.lastResponse!!.status.value, world.lastBodyText)
    }

    @Then("their display name is {string}")
    fun displayNameIs(name: String) {
        assertEquals(name, world.lastJson["displayName"]?.jsonPrimitive?.contentOrNull, world.lastBodyText)
    }

    @Then("reading the profile returns that address")
    fun readingReturnsAddress() {
        val address = readAs("usr_mina", "usr_mina")["shippingAddress"]?.jsonObject
        assertNotNull(address, world.lastBodyText)
        expected.forEach { (k, v) -> assertEquals(v, address[k]?.jsonPrimitive?.contentOrNull) }
    }

    @When("{string} updates profile {string}")
    fun updatesOther(actor: String, target: String) {
        update(actor, target, """{"displayName":"Hijacked"}""")
    }

    @Then("{string} is unchanged")
    fun unchanged(label: String) {
        assertEquals(snapshots[label], readAs(label, label))
    }

    @When("{string} updates their profile with {string} = {string}")
    fun updatesField(actor: String, field: String, value: String) {
        val actual = if (value == "<61 characters>") "x".repeat(61) else value
        val body = if (field == "postalCode") {
            """{"shippingAddress":{"line1":"1 Main","city":"Portland","region":"OR","postalCode":"$actual","country":"US"}}"""
        } else {
            """{"$field":"$actual"}"""
        }
        update(actor, actor, body)
    }

    @Given("{string} read their profile at version {int}")
    fun readAtVersion(actor: String, version: Int) {
        var current = readAs(actor, actor)["version"]!!.jsonPrimitive.intOrNull!!
        while (current < version) {
            world.putProfile(world.resolveUserId(actor), """{"displayName":"Mina v${current + 1}"}""", token = world.tokenForAlias(actor))
            current = world.lastJson["version"]!!.jsonPrimitive.intOrNull!!
        }
        assertEquals(version, current)
    }

    @Given("another session has since saved version {int}")
    fun anotherSessionSaved(version: Int) {
        world.putProfile(world.resolveUserId("usr_mina"), """{"displayName":"From another tab"}""", token = world.tokenForAlias("usr_mina"))
        assertEquals(version, world.lastJson["version"]?.jsonPrimitive?.intOrNull, world.lastBodyText)
    }

    @When("{string} submits an update based on version {int}")
    fun submitsBasedOnVersion(actor: String, version: Int) {
        world.putProfile(world.resolveUserId(actor), """{"version":$version,"displayName":"Stale edit"}""", token = world.tokenForAlias(actor))
    }

    @When("{string} updates their profile with email {string}")
    fun updatesEmail(actor: String, email: String) {
        update(actor, actor, """{"email":"$email"}""")
    }

    @Then("the stored email is still {string}")
    fun storedEmail(email: String) {
        assertEquals(email, readAs("usr_mina", "usr_mina")["email"]?.jsonPrimitive?.contentOrNull)
    }

    @When("an anonymous client reads profile {string}")
    fun anonymousReads(target: String) {
        world.call(HttpMethod.Get, "/api/v1/profile/${world.resolveUserId(target)}", token = null)
    }
}
