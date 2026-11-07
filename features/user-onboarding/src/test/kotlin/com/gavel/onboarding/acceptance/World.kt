package com.gavel.onboarding.acceptance

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gavel.kernel.auth.TestIdentityProvider
import com.gavel.kernel.testing.TestDatabase
import com.gavel.onboarding.config.UserOnboardingSettings
import com.gavel.onboarding.domain.CreditPolicy
import com.gavel.onboarding.domain.SellerRequirements
import com.gavel.onboarding.service.IdentityProviderAdmin
import java.time.Duration
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Per-scenario state shared by all step classes (injected by picocontainer). */
class World {
    val clock = MutableClock(Instant.parse("2026-11-07T10:00:00Z"))
    val idp = TestIdentityProvider(clock = clock)
    val idpAdmin = RecordingIdentityProviderAdmin()

    /** "the configured policy" of USR-012; scenarios may pin or change it. */
    var policy = CreditPolicy("test-policy-1", minimumScore = 40, incomeMinorPerPoint = 100_000, wealthMinorPerPoint = 100_000, maxPointsPerFactor = 40, retryAfter = Duration.ofDays(30))
    var sellerRequirements = SellerRequirements.DEFAULT
    val settings = UserOnboardingSettings(
        assessmentEncryptionKey = UserOnboardingSettings.DEV_ENCRYPTION_KEY,
        creditPolicy = policy,
        sellerRequirements = sellerRequirements,
        internalApiToken = "operator-secret",
        bootstrapRateLimitPerMinute = 60,
    )
    var app: TestApplication? = null
    val client: HttpClient get() = checkNotNull(app) { "scenario not started" }.client

    /** Token of the most recently signed-in provider user. */
    var currentToken: String? = null
    var currentEmail: String? = null

    /** Scenario labels such as "usr_mina" mapped to the real opaque user ids. */
    val userIdAliases = mutableMapOf<String, String>()
    val tokensByEmail = mutableMapOf<String, String>()
    val userIdByEmail = mutableMapOf<String, String>()
    val emailByAlias = mutableMapOf<String, String>()

    /** Secrets the scenario typed at the provider; must never reach application logs. */
    val secretsTyped = mutableListOf<String>()

    var lastResponse: HttpResponse? = null
    var lastBodyText: String = ""
    val responses = mutableListOf<Pair<HttpResponse, String>>()

    val logs = ListAppender<ILoggingEvent>().apply { start() }

    fun start() {
        TestDatabase.migrate("classpath:db/migration/shared", "classpath:db/migration/onboarding")
        TestDatabase.truncate("users", "event_outbox", "audit_log")
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logs)
        app = TestApplication { application { onboardingTestModule(this@World) } }
    }

    fun stop() {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logs)
        runBlocking { app?.stop() }
        app = null
    }

    fun signInAs(email: String): String {
        val token = idp.tokenFor(email)
        currentToken = token
        currentEmail = email.trim().lowercase()
        tokensByEmail[currentEmail!!] = token
        return token
    }

    fun call(method: HttpMethod, path: String, token: String? = currentToken, body: String? = null, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        runBlocking {
            // Distinct timestamps per request keep outbox and audit ordering unambiguous.
            clock.advance(Duration.ofSeconds(1))
            val response = client.request(path) {
                this.method = method
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                configure()
            }
            record(response)
        }

    suspend fun record(response: HttpResponse): HttpResponse {
        lastResponse = response
        lastBodyText = response.bodyAsText()
        responses += response to lastBodyText
        return response
    }

    val lastJson: JsonObject get() = Json.parseToJsonElement(lastBodyText).jsonObject

    fun resolveUserId(label: String): String = userIdAliases[label] ?: label

    fun logLines(): List<String> = logs.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
}

class MutableClock(private var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId) = this
    override fun instant(): Instant = now
    fun set(instant: Instant) { now = instant }
    fun advance(duration: java.time.Duration) { now = now.plus(duration) }
}

/** Records provider admin calls; can be told to fail to simulate an unreachable admin API. */
class RecordingIdentityProviderAdmin : IdentityProviderAdmin {
    val disabled = mutableListOf<String>()
    val enabled = mutableListOf<String>()
    var unreachable = false

    override suspend fun disableUser(subject: String) {
        if (unreachable) throw java.io.IOException("Keycloak admin API unreachable")
        disabled += subject
    }

    override suspend fun enableUser(subject: String) {
        if (unreachable) throw java.io.IOException("Keycloak admin API unreachable")
        enabled += subject
    }
}

// ---- Scenario fixtures shared by several feature files ----

/** Puts an account for [email] into [state] through the public API, as a real client would. */
fun World.ensureAccount(email: String, state: String, label: String? = null): String {
    val normalized = email.trim().lowercase()
    val existing = userIdByEmail[normalized]
    if (existing != null) {
        // Idempotent: a scenario may describe the same account twice.
        signInAs(normalized)
        call(HttpMethod.Get, "/api/v1/profile/$existing")
        val current = lastJson["accountState"]!!.jsonPrimitive.content
        when {
            current == state -> Unit
            state == "SUSPENDED" && current == "ACTIVE" -> {
                operatorCall(HttpMethod.Post, "/internal/accounts/$existing/suspend", """{"reason":"FRAUD_REVIEW"}""")
                check(lastResponse?.status?.value == 200) { "suspension failed: $lastBodyText" }
            }
            else -> error("fixture cannot move $normalized from $current to $state")
        }
        if (label != null) { userIdAliases[label] = existing; emailByAlias[label] = normalized }
        return existing
    }
    when (state) {
        "PENDING_VERIFICATION" -> {
            idp.registerPasswordUser(normalized)
            signInAs(normalized)
            me()
        }
        "PENDING_CREDIT_ELIGIBILITY" -> {
            idp.signInWithGoogle(normalized)
            signInAs(normalized)
            me()
        }
        "ACTIVE" -> {
            ensureAccount(normalized, "PENDING_CREDIT_ELIGIBILITY")
            val id = userIdOf(normalized)
            putProfile(id, """{"assessment":{"age":34,"annualIncome":6800000,"netWealth":1500000}}""")
            check(lastResponse?.status?.value == 200) { "activation failed: $lastBodyText" }
        }
        "SUSPENDED" -> {
            ensureAccount(normalized, "ACTIVE")
            operatorCall(HttpMethod.Post, "/internal/accounts/${userIdOf(normalized)}/suspend", """{"reason":"FRAUD_REVIEW"}""")
            check(lastResponse?.status?.value == 200) { "suspension failed: $lastBodyText" }
        }
        else -> error("unknown state $state")
    }
    val id = userIdOf(normalized)
    if (label != null) {
        userIdAliases[label] = id
        emailByAlias[label] = normalized
    }
    return id
}

fun World.me(): JsonObject {
    call(HttpMethod.Get, "/api/v1/me")
    check(lastResponse?.status?.value == 200) { "bootstrap failed: $lastBodyText" }
    val json = lastJson
    userIdByEmail[currentEmail!!] = json["userId"]!!.jsonPrimitive.content
    return json
}

fun World.userIdOf(email: String): String = userIdByEmail[email.trim().lowercase()] ?: error("no account bootstrapped for $email")

fun World.tokenForAlias(label: String): String {
    val email = emailByAlias[label] ?: error("unknown user label $label")
    return tokensByEmail[email] ?: idp.tokenFor(email)
}

fun World.putProfile(userId: String, body: String, token: String? = currentToken): HttpResponse =
    call(HttpMethod.Put, "/api/v1/profile/$userId", token = token, body = body)

fun World.operatorCall(method: HttpMethod, path: String, body: String? = null): HttpResponse =
    call(method, path, token = null, body = body) { header("X-Internal-Token", "operator-secret") }
