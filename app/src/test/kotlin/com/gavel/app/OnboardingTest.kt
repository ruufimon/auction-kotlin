package com.gavel.app

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OnboardingTest {
    private val password = "Password123"

    private suspend fun TestEnv.register(email: String) =
        post("/api/v1/auth/register", mapOf("email" to email, "password" to password, "displayName" to "Alex Morgan", "acceptedTerms" to true))

    private suspend fun TestEnv.refresh(cookie: String) =
        post("/api/v1/auth/refresh") { header(HttpHeaders.Cookie, "gavel_refresh=$cookie") }

    @Test
    fun `registration creates a pending account that cannot sign in or bid`() = gavelTest {
        val response = register("Alex@Example.com ")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("PENDING_VERIFICATION", body.str("status"))
        assertEquals("alex@example.com", body.str("email"))
        assertEquals(false, body.getValue("emailVerified").jsonPrimitive.boolean)

        val signIn = post("/api/v1/auth/sign-in", mapOf("email" to "alex@example.com", "password" to password))
        assertEquals(HttpStatusCode.Forbidden, signIn.status)
        assertEquals("EMAIL_NOT_VERIFIED", signIn.json().code())
    }

    @Test
    fun `registration validates fields and rejects duplicate emails`() = gavelTest {
        val invalid = post("/api/v1/auth/register", mapOf("email" to "nope", "password" to "short", "displayName" to "A", "acceptedTerms" to false))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val fields = invalid.json().arr("fields").map { it.jsonObjectField("field") }.toSet()
        assertEquals(setOf("email", "password", "displayName", "acceptedTerms"), fields)

        assertEquals(HttpStatusCode.Created, register("dup@example.com").status)
        val duplicate = register("DUP@example.com")
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("EMAIL_TAKEN", duplicate.json().code())
    }

    @Test
    fun `a verification token works once and fails after use or expiry`() = gavelTest {
        register("once@example.com")
        val token = email.lastTokenFor("once@example.com")!!
        val first = post("/api/v1/auth/verify-email", mapOf("token" to token))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals("ACTIVE", first.json().obj("user").str("status"))
        val reuse = post("/api/v1/auth/verify-email", mapOf("token" to token))
        assertEquals(HttpStatusCode.UnprocessableEntity, reuse.status)
        assertEquals("VERIFICATION_TOKEN_INVALID", reuse.json().code())

        register("late@example.com")
        clock.advance(Duration.ofHours(25))
        val expired = post("/api/v1/auth/verify-email", mapOf("token" to email.lastTokenFor("late@example.com")!!))
        assertEquals("VERIFICATION_TOKEN_INVALID", expired.json().code())

        // A resend issues a fresh, working token.
        assertEquals(HttpStatusCode.Accepted, post("/api/v1/auth/verify-email/resend", mapOf("email" to "late@example.com")).status)
        assertEquals(HttpStatusCode.OK, post("/api/v1/auth/verify-email", mapOf("token" to email.lastTokenFor("late@example.com")!!)).status)
    }

    @Test
    fun `sign in rejects wrong passwords without revealing accounts`() = gavelTest {
        val user = registerAndVerify()
        val wrong = post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to "Wrong12345"))
        val unknown = post("/api/v1/auth/sign-in", mapOf("email" to "ghost@example.com", "password" to password))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(wrong.json().code(), unknown.json().code())

        val ok = post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to password))
        assertEquals(HttpStatusCode.OK, ok.status)
        val cookie = ok.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("gavel_refresh=") }
        assertTrue("HttpOnly" in cookie && "Path=/api/v1/auth" in cookie, cookie)
    }

    @Test
    fun `refresh rotates tokens and reuse revokes the family`() = gavelTest {
        val user = registerAndVerify()
        val first = refresh(user.refreshCookie)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val rotated = first.refreshCookie()!!
        assertNotEquals(user.refreshCookie, rotated)

        // Replaying the old token is treated as theft: it fails and kills the newer token too.
        val reuse = refresh(user.refreshCookie)
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)
        assertEquals("REFRESH_TOKEN_INVALID", reuse.json().code())
        assertEquals(HttpStatusCode.Unauthorized, refresh(rotated).status)
    }

    @Test
    fun `sign out revokes the refresh token`() = gavelTest {
        val user = registerAndVerify()
        assertEquals(HttpStatusCode.NoContent, post("/api/v1/auth/sign-out") { header(HttpHeaders.Cookie, "gavel_refresh=${user.refreshCookie}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(user.refreshCookie).status)
    }

    @Test
    fun `profile updates validate input and honour If-Match`() = gavelTest {
        val user = registerAndVerify(shippingAddress = false)
        val me = get("/api/v1/me", user.token).json()
        val version = me.long("version")

        val bad = patch("/api/v1/me", mapOf("shippingAddress" to address() + ("country" to "USA")), user.token)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("shippingAddress.country", bad.json().arr("fields").single().jsonObjectField("field"))

        val ok = patch("/api/v1/me", mapOf("displayName" to "New Name", "preferences" to mapOf("favoriteCategories" to listOf("cameras"))), user.token) {
            header(HttpHeaders.IfMatch, "\"$version\"")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("New Name", ok.json().str("displayName"))

        val stale = patch("/api/v1/me", mapOf("displayName" to "Stale"), user.token) { header(HttpHeaders.IfMatch, "\"$version\"") }
        assertEquals(HttpStatusCode.Conflict, stale.status)
    }

    @Test
    fun `completing the seller profile enables selling on the same account`() = gavelTest {
        val user = registerAndVerify()
        val before = get("/api/v1/me", user.token).json()
        assertEquals(false, before.getValue("sellerEligible").jsonPrimitive.boolean)
        assertTrue(before.arr("missingSellerFields").isNotEmpty())

        val after = put("/api/v1/me/seller-profile", mapOf("bio" to "I sell restored mid-century furniture.", "handlingTimeDays" to 3, "originAddress" to address()), user.token).json()
        assertEquals(true, after.getValue("sellerEligible").jsonPrimitive.boolean)
        assertEquals(user.id, after.str("id"))
    }

    @Test
    fun `suspension blocks refresh and access immediately and reactivation restores it`() = gavelTest {
        val user = registerAndVerify()
        val suspend = post("/admin/v1/accounts/${user.id}/suspend", mapOf("operator" to "ops@gavel", "reason" to "fraud review")) {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }
        assertEquals(HttpStatusCode.OK, suspend.status, suspend.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/me", user.token).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(user.refreshCookie).status)
        assertEquals("ACCOUNT_SUSPENDED", post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to password)).json().code())

        val wrongToken = post("/admin/v1/accounts/${user.id}/reactivate", mapOf("operator" to "ops")) { header(HttpHeaders.Authorization, "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)

        post("/admin/v1/accounts/${user.id}/reactivate", mapOf("operator" to "ops@gavel")) { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }
        assertEquals(HttpStatusCode.OK, post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to password)).status)
    }
}

fun kotlinx.serialization.json.JsonElement.jsonObjectField(name: String): String =
    (this as kotlinx.serialization.json.JsonObject).getValue(name).jsonPrimitive.content
