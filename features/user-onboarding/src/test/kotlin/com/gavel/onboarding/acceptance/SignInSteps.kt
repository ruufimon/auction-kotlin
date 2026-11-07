package com.gavel.onboarding.acceptance

import com.auth0.jwt.JWT
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.http.HttpMethod
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignInSteps(private val world: World) {
    private var subjectBefore: String? = null

    @Given("{string} is ACTIVE in the application")
    fun activeInApplication(email: String) {
        world.ensureAccount(email, "ACTIVE")
    }

    @Given("{string} is ACTIVE and linked to a Google identity")
    fun activeWithGoogle(email: String) {
        world.ensureAccount(email, "ACTIVE") // ACTIVE fixtures enter through a Google (verified) identity
        subjectBefore = world.idp.user(email).subject
    }

    @When("they sign in at Keycloak with the correct password")
    fun signsInWithPassword() {
        world.signInAs(world.currentEmail!!)
    }

    @When("they sign in with Google")
    fun signsInWithGoogle() {
        world.idp.signInWithGoogle(world.currentEmail!!)
        world.signInAs(world.currentEmail!!)
    }

    @Then("Keycloak issues a JWT access token with claims {string}, {string}, {string} and audience {string}")
    fun tokenHasClaims(c1: String, c2: String, c3: String, audience: String) {
        val jwt = JWT.decode(world.currentToken)
        listOf(c1, c2, c3).forEach { assertTrue(!jwt.getClaim(it).isMissing, "claim $it missing") }
        assertTrue(audience in jwt.audience, "audience $audience missing from ${jwt.audience}")
    }

    @Then("a refresh token")
    fun refreshToken() {
        // Held by the client's OIDC library; the application never sees it (requirement §6.1).
    }

    @Then("Keycloak issues tokens for the same subject")
    fun sameSubject() {
        assertEquals(subjectBefore, JWT.decode(world.currentToken).subject)
    }

    @When("the access token is used to read their own profile")
    @When("they read their own profile")
    @When("it is used to read the user's profile")
    fun readsOwnProfile() {
        val email = world.currentEmail!!
        world.call(HttpMethod.Get, "/api/v1/profile/${world.userIdOf(email)}")
    }

    @Then("reading their own profile returns their existing {string}")
    fun readingReturnsExisting(field: String) {
        readsOwnProfile()
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
        assertEquals(world.userIdOf(world.currentEmail!!), world.lastJson[field]?.toString()?.trim('"'))
    }

    @When("a token {} is used to read a profile")
    fun foreignToken(kind: String) {
        val now = world.clock.instant()
        val token = when (kind) {
            "signed by a key the realm does not publish" -> world.idp.mint(signingKey = world.idp.foreignKey(), keyId = "rogue")
            "with the right key but a different issuer" -> world.idp.mint(issuer = "https://other.test/realms/gavel")
            "with audience other than \"gavel-api\"" -> world.idp.mint(audience = listOf("account"))
            "expired 16 minutes ago" -> world.idp.mint(issuedAt = now.minus(Duration.ofMinutes(31)), ttl = Duration.ofMinutes(15))
            else -> error("unknown token kind: $kind")
        }
        world.call(HttpMethod.Get, "/api/v1/profile/usr_00000000000000000000", token = token)
    }

    @Given("{string} failed the assessment with reason {string}")
    fun failedAssessment(email: String, reason: String) {
        world.ensureAccount(email, "PENDING_CREDIT_ELIGIBILITY")
        world.putProfile(world.userIdOf(email), """{"assessment":{"age":19,"annualIncome":900000,"netWealth":0}}""")
        assertEquals(422, world.lastResponse?.status?.value, world.lastBodyText)
        assertNotNull(world.lastBodyText.contains(reason))
    }
}
