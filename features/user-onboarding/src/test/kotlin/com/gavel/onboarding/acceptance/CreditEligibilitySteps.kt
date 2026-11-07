package com.gavel.onboarding.acceptance

import com.gavel.kernel.testing.TestDatabase
import com.gavel.onboarding.domain.AssessmentInputs
import com.gavel.onboarding.domain.EligibilityOutcome
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreditEligibilitySteps(private val world: World) {
    private var correlationId: String? = null
    private var submitted: List<String> = emptyList()

    @Given("the configured scoring policy is version {string} with a known threshold")
    fun policyVersion(version: String) {
        world.policy = world.policy.copy(version = version)
    }

    @Given("under {string} age {int}, income {int} and wealth {int} meet the threshold")
    fun fixtureMeets(version: String, age: Int, income: Int, wealth: Int) {
        assertEquals(version, world.policy.version)
        assertEquals(EligibilityOutcome.ELIGIBLE, world.policy.evaluate(AssessmentInputs(age, income.toLong(), wealth.toLong())).outcome)
    }

    @Given("under {string} age {int}, income {int} and wealth {int} do not meet the threshold")
    fun fixtureFails(version: String, age: Int, income: Int, wealth: Int) {
        assertEquals(version, world.policy.version)
        assertEquals(EligibilityOutcome.NOT_ELIGIBLE, world.policy.evaluate(AssessmentInputs(age, income.toLong(), wealth.toLong())).outcome)
    }

    @Given("{string} is in state {string} and signed in")
    @Given("{string} is in state {string}")
    fun inState(email: String, state: String) {
        world.ensureAccount(email, state)
    }

    private fun submit(age: String, income: String, wealth: String, email: String = world.currentEmail!!) {
        submitted = listOf(age, income, wealth)
        correlationId = UUID.randomUUID().toString()
        world.call(HttpMethod.Put, "/api/v1/profile/${world.userIdOf(email)}", token = world.tokensByEmail[email],
            body = """{"assessment":{"age":$age,"annualIncome":$income,"netWealth":$wealth}}""") {
            header(HttpHeaders.XRequestId, correlationId!!)
        }
    }

    @When("^they submit age (\\S+), annual income (\\S+) and net wealth (\\S+)(?: in minor units)?$")
    fun submits(age: String, income: String, wealth: String) = submit(age, income, wealth)

    @Then("{string} is still {string}")
    fun profileFieldStill(field: String, expected: String) {
        assertEquals(expected, profile()[field]?.jsonPrimitive?.contentOrNull)
    }

    @When("they submit a passing assessment")
    @Given("their assessment passed")
    fun submitsPassing() = submit("34", "6800000", "1500000")

    @When("they submit a passing assessment with income {int} and wealth {int}")
    fun submitsPassingWith(income: Int, wealth: Int) = submit("34", income.toString(), wealth.toString())

    @Given("their assessment failed")
    fun submitsFailing() {
        submit("19", "900000", "0")
        assertEquals(422, world.lastResponse?.status?.value, world.lastBodyText)
    }

    @When("{string} submits assessment inputs")
    fun otherSubmits(email: String) = submit("34", "6800000", "1500000", email)

    private fun profile() = run {
        val email = world.currentEmail!!
        world.call(HttpMethod.Get, "/api/v1/profile/${world.userIdOf(email)}", token = world.tokensByEmail[email])
        world.lastJson
    }

    @Then("the stored assessment has outcome {string}, reason {string}, policy version {string} and an {string} timestamp")
    fun storedAssessmentFull(outcome: String, reason: String, version: String, timestampField: String) {
        val p = profile()
        assertEquals(outcome, p["creditEligibilityOutcome"]?.jsonPrimitive?.contentOrNull)
        assertEquals(reason, p["eligibilityReason"]?.jsonPrimitive?.contentOrNull)
        assertEquals(version, p["assessmentPolicyVersion"]?.jsonPrimitive?.contentOrNull)
        Instant.parse(assertNotNull(p[timestampField]?.jsonPrimitive?.contentOrNull))
    }

    @Then("the stored assessment has outcome {string} and policy version {string}")
    fun storedAssessment(outcome: String, version: String) {
        val p = profile()
        assertEquals(outcome, p["creditEligibilityOutcome"]?.jsonPrimitive?.contentOrNull)
        assertEquals(version, p["assessmentPolicyVersion"]?.jsonPrimitive?.contentOrNull)
    }

    @Then("the event payload contains the outcome and policy version but not the inputs")
    fun eventPayload() {
        val payload = TestDatabase.query("SELECT payload::text FROM event_outbox WHERE event_type = 'user-onboarding.credit-eligibility-assessed' ORDER BY created_at DESC LIMIT 1") { rs -> rs.next(); rs.getString(1) }
        assertTrue(payload.contains("ELIGIBLE") && payload.contains(world.policy.version), payload)
        submitted.forEach { assertFalse(payload.contains(it), "input $it leaked into event payload") }
    }

    @Then("no assessment is recorded")
    fun noAssessment() {
        assertNull(profile()["creditEligibilityOutcome"]?.jsonPrimitive?.contentOrNull)
    }

    @Then("the users row stores age, annual income and net wealth only in encrypted columns")
    fun storedEncrypted() {
        val row = TestDatabase.query("SELECT age_enc, annual_income_enc, net_wealth_enc FROM users WHERE email = '${world.currentEmail}'") { rs ->
            rs.next(); listOf(rs.getString(1), rs.getString(2), rs.getString(3))
        }
        row.forEach { assertNotNull(it, "encrypted column empty") }
        submitted.forEach { value -> row.forEach { assertFalse(it.contains(value)) } }
    }

    @Then("the plaintext values do not appear in any database column or index")
    fun noPlaintextAnywhere() {
        val whole = TestDatabase.query("SELECT users::text FROM users WHERE email = '${world.currentEmail}'") { rs -> rs.next(); rs.getString(1) }
        listOf("6800000", "1500000").forEach { assertFalse(whole.contains(it), "plaintext $it in users row") }
    }

    @Then("the application logs for the request do not contain {string} or {string} or the age")
    fun logsHaveNoInputs(income: String, wealth: String) {
        val leaks = world.logLines().filter { it.contains(income) || it.contains(wealth) || Regex("\\bage\\b.*\\b34\\b").containsMatchIn(it) }
        assertTrue(leaks.isEmpty(), "assessment inputs leaked into logs: $leaks")
    }

    @Then("the logs do contain the correlation id and the outcome")
    fun logsHaveOutcome() {
        val line = world.logLines().firstOrNull { it.contains(correlationId!!) }
        assertNotNull(line, "no log line with correlation id $correlationId")
        assertTrue(line.contains("ELIGIBLE"), line)
    }

    @Given("the configured policy is changed to version {string} after an assessment under {string}")
    fun policyChangedAfterAssessment(newVersion: String, oldVersion: String) {
        assertEquals(oldVersion, world.policy.version)
        submitsPassing()
        assertEquals(200, world.lastResponse?.status?.value, world.lastBodyText)
        world.policy = world.policy.copy(version = newVersion)
    }

    @Given("their assessment failed under {string} which allows a retry after {int} days")
    fun failedWithRetryWindow(version: String, days: Int) {
        world.policy = world.policy.copy(version = version, retryAfter = Duration.ofDays(days.toLong()))
        submitsFailing()
    }

    @When("they submit new inputs the same day")
    fun retriesSameDay() = submitsPassing()

    @Then("the error details include the earliest retry time")
    fun detailsIncludeRetryTime() {
        val value = world.lastJson["details"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["field"]?.jsonPrimitive?.contentOrNull == "earliestRetryAt" }
            ?.get("value")?.jsonPrimitive?.contentOrNull
        Instant.parse(assertNotNull(value, "no earliestRetryAt detail in ${world.lastBodyText}"))
    }
}
