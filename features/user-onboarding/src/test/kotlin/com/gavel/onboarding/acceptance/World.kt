package com.gavel.onboarding.acceptance

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gavel.kernel.auth.TestIdentityProvider
import com.gavel.kernel.testing.TestDatabase
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
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Per-scenario state shared by all step classes (injected by picocontainer). */
class World {
    val clock = MutableClock(Instant.parse("2026-11-07T10:00:00Z"))
    val idp = TestIdentityProvider(clock = clock)
    var app: TestApplication? = null
    val client: HttpClient get() = checkNotNull(app) { "scenario not started" }.client

    /** Token of the most recently signed-in provider user. */
    var currentToken: String? = null
    var currentEmail: String? = null

    /** Scenario labels such as "usr_mina" mapped to the real opaque user ids. */
    val userIdAliases = mutableMapOf<String, String>()
    val tokensByEmail = mutableMapOf<String, String>()

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
