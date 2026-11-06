package com.gavel.app

import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.Money
import com.gavel.kernel.MoneyPolicy
import com.gavel.onboarding.infrastructure.Argon2PasswordHasher
import com.gavel.onboarding.infrastructure.CapturingEmailSender
import com.gavel.settlement.infrastructure.SandboxPaymentProvider
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.getAllRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** A clock tests move by hand. */
class MutableClock(@Volatile var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(duration: Duration) {
        now += duration
    }
}

/** One PostgreSQL container and schema per test JVM; tables are truncated between tests. */
object TestDatabase {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    }

    val dataSource: HikariDataSource by lazy {
        dataSource(DatabaseConfig(container.jdbcUrl, container.username, container.password, maxPoolSize = 20)).also(::migrate)
    }

    fun reset() {
        dataSource.connection.use { c ->
            val tables = c.createStatement().executeQuery(
                """
                SELECT schemaname || '.' || tablename FROM pg_tables
                WHERE schemaname = ANY (ARRAY['platform','onboarding','catalog','bidding','settlement'])
                  AND tablename <> 'flyway_schema_history' AND NOT (schemaname = 'catalog' AND tablename = 'categories')
                """.trimIndent(),
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            c.createStatement().execute("TRUNCATE ${tables.joinToString()} CASCADE")
            c.commit()
        }
    }
}

const val WEBHOOK_SECRET = "test-webhook-secret-123456"
const val ADMIN_TOKEN = "test-admin-token-0123456789abcdefghijkl"

val testJson = Json { ignoreUnknownKeys = true }

class TestEnv(val clock: MutableClock, val email: CapturingEmailSender, val gavel: Gavel, val client: HttpClient, val application: io.ktor.server.application.Application) {
    /** Every served (method, path) pair, ignoring transparent selectors like authentication and rate limits. */
    fun gavelRoutes(): Set<Pair<String, String>> =
        application.pluginOrNull(io.ktor.server.routing.RoutingRoot)!!.getAllRoutes().mapNotNull { route ->
            val text = route.toString()
            val method = Regex("\\(method:([A-Z]+)\\)").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val path = text.replace(Regex("/?\\([^)]*\\)"), "").replace(Regex("/+"), "/").ifEmpty { "/" }
            method to path
        }.toSet()

    val usd = CurrencyCode("USD")
    fun usd(minor: Long) = Money(minor, usd)

    // ---- HTTP helpers ----

    suspend fun get(path: String, token: String? = null, block: HttpRequestBuilder.() -> Unit = {}) =
        client.get(path) { token?.let { bearerAuth(it) }; block() }

    suspend fun post(path: String, body: Any? = null, token: String? = null, block: HttpRequestBuilder.() -> Unit = {}) =
        client.post(path) {
            token?.let { bearerAuth(it) }
            if (body != null) jsonBody(body)
            block()
        }

    suspend fun patch(path: String, body: Any, token: String? = null, block: HttpRequestBuilder.() -> Unit = {}) =
        client.patch(path) { token?.let { bearerAuth(it) }; jsonBody(body); block() }

    suspend fun put(path: String, body: Any, token: String? = null) =
        client.put(path) { token?.let { bearerAuth(it) }; jsonBody(body) }

    // ---- Scenario helpers ----

    data class User(val id: String, val email: String, val token: String, val refreshCookie: String)

    suspend fun registerAndVerify(name: String = "Buyer ${UUID.randomUUID().toString().take(6)}", shippingAddress: Boolean = true): User {
        val email = "${UUID.randomUUID()}@example.com"
        val registered = post("/api/v1/auth/register", mapOf("email" to email, "password" to "Password123", "displayName" to name, "phone" to "+1 555 010 2000", "acceptedTerms" to true))
        assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
        val verified = post("/api/v1/auth/verify-email", mapOf("token" to this.email.lastTokenFor(email)!!))
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        val json = verified.json()
        val user = User(json.obj("user").str("id"), email, json.str("accessToken"), verified.refreshCookie()!!)
        if (shippingAddress) {
            val r = patch("/api/v1/me", mapOf("shippingAddress" to address()), user.token)
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        }
        return user
    }

    /** Signs in again, e.g. after moving the clock past the access-token lifetime. */
    suspend fun reauth(user: User): User {
        val r = post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to "Password123"))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return user.copy(token = r.json().str("accessToken"), refreshCookie = r.refreshCookie()!!)
    }

    suspend fun seller(): User {
        val user = registerAndVerify("Seller ${UUID.randomUUID().toString().take(6)}")
        val r = put("/api/v1/me/seller-profile", mapOf("bio" to "Careful seller of vintage cameras and watches.", "handlingTimeDays" to 2, "originAddress" to address()), user.token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return user
    }

    fun address() = mapOf("line1" to "1 Market Street", "city" to "Portland", "region" to "Oregon", "postalCode" to "97201", "country" to "US")

    fun listingInput(
        startAt: Instant = clock.instant().minusSeconds(1),
        endAt: Instant = clock.instant() + Duration.ofHours(2),
        startingMinor: Long = 10_000,
        shippingMinor: Long = 1_500,
        title: String = "Leica M6 Classic 0.72",
    ): Map<String, Any?> = mapOf(
        "title" to title,
        "description" to "A beautifully preserved camera with an accurate meter and clean optics.",
        "category" to "cameras",
        "condition" to "EXCELLENT",
        "images" to listOf("https://images.example.com/leica.jpg"),
        "startingPrice" to money(startingMinor),
        "shippingPrice" to money(shippingMinor),
        "origin" to mapOf("city" to "Portland", "region" to "Oregon", "country" to "US"),
        "startAt" to startAt.truncatedTo(ChronoUnit.MILLIS).toString(),
        "endAt" to endAt.truncatedTo(ChronoUnit.MILLIS).toString(),
    )

    fun money(minor: Long) = mapOf("amountMinor" to minor, "currency" to "USD")

    /** Creates and publishes a listing; returns its id. */
    suspend fun publishedListing(seller: User, input: Map<String, Any?> = listingInput()): String {
        val created = post("/api/v1/me/listings", input, seller.token)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = created.json().str("id")
        val published = post("/api/v1/me/listings/$id/publish", token = seller.token)
        assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())
        return id
    }

    suspend fun bid(listingId: String, user: User, minor: Long, key: String = UUID.randomUUID().toString()) =
        post("/api/v1/listings/$listingId/bids", mapOf("amount" to money(minor)), user.token) { header("Idempotency-Key", key) }

    suspend fun webhook(type: String, reference: String, amountMinor: Long, eventId: String = UUID.randomUUID().toString(), secret: String = WEBHOOK_SECRET): HttpResponse {
        val body = """{"id":"$eventId","type":"$type","data":{"reference":"$reference","amountMinor":$amountMinor,"currency":"USD"}}"""
        val t = clock.instant().epochSecond
        return client.post("/api/v1/payments/webhooks/sandbox") {
            header(SandboxPaymentProvider.SIGNATURE_HEADER, "t=$t,v1=${SandboxPaymentProvider.sign(secret, t, body)}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    fun runJobs() = gavel.runJobsOnce()

    private fun HttpRequestBuilder.bearerAuth(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(toJsonElement(body).toString())
    }
}

fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJsonElement(it.value) })
    is List<*> -> JsonArray(value.map(::toJsonElement))
    is Number -> kotlinx.serialization.json.JsonPrimitive(value)
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
    else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
}

suspend fun HttpResponse.json(): JsonObject = testJson.parseToJsonElement(bodyAsText()).jsonObject
fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
fun JsonObject.obj(key: String) = getValue(key).jsonObject
fun JsonObject.arr(key: String) = getValue(key).jsonArray
fun JsonObject.code() = str("code")

fun HttpResponse.refreshCookie(): String? =
    headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("gavel_refresh=") }
        ?.substringAfter("gavel_refresh=")?.substringBefore(';')?.takeIf { it.isNotEmpty() }

/** Runs a test against a fresh database state and a full in-process app. */
fun gavelTest(start: Instant = Instant.parse("2026-11-03T12:00:00Z"), block: suspend TestEnv.() -> Unit) {
    TestDatabase.reset()
    val clock = MutableClock(start)
    val email = CapturingEmailSender()
    val config = AppConfig(
        port = 0,
        database = DatabaseConfig("unused", null, null),
        jwtSecret = "test-jwt-secret-that-is-long-enough-123",
        webOrigins = listOf("http://localhost:3000"),
        webBaseUrl = "http://localhost:3000",
        cookieSecure = false,
        money = MoneyPolicy(CurrencyCode("USD"), Money(100, CurrencyCode("USD"))),
        paymentWindow = Duration.ofHours(24),
        paymentWebhookSecret = WEBHOOK_SECRET,
        checkoutBaseUrl = "https://pay.example.com/checkout",
        adminToken = ADMIN_TOKEN,
        metricsToken = null,
        seedDemoData = false,
        serviceVersion = "test",
    )
    val gavel = Gavel(config, TestDatabase.dataSource, clock, email, Argon2PasswordHasher(memoryKiB = 1024, iterations = 1))
    testApplication {
        lateinit var app: io.ktor.server.application.Application
        application {
            app = this
            gavelModule(gavel, RateLimits(authPerMinute = 10_000, webhooksPerMinute = 10_000))
        }
        startApplication()
        val client = createClient { install(ContentNegotiation) { json(testJson) } }
        TestEnv(clock, email, gavel, client, app).block()
    }
}
