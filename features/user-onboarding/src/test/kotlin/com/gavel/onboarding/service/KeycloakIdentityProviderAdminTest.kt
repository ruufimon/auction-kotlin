package com.gavel.onboarding.service

import com.gavel.onboarding.config.IdentityProviderAdminSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Issue #22 (AFA-10): suspension reaches Keycloak's admin API, against a fake Keycloak. */
class KeycloakIdentityProviderAdminTest {
    private data class Seen(val method: String, val path: String, val auth: String?, val body: String)

    private val subject = "8f14e45f-ceea-467f-a8b9-2c1e0a5c7a10"
    private val seen = CopyOnWriteArrayList<Seen>()
    private var adminStatus = 204
    private var tokenStatus = 200
    private var tokenCount = 0
    private lateinit var server: HttpServer
    private lateinit var admin: KeycloakIdentityProviderAdmin

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            seen += Seen(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
            val (status, response) = if (exchange.requestURI.path.endsWith("/protocol/openid-connect/token")) {
                tokenCount++
                tokenStatus to """{"access_token":"admin-token-$tokenCount","expires_in":300,"token_type":"Bearer"}"""
            } else {
                adminStatus to ""
            }
            if (response.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            exchange.close()
        }
        server.start()
        val issuer = "http://127.0.0.1:${server.address.port}/realms/gavel"
        admin = KeycloakIdentityProviderAdmin(issuer, IdentityProviderAdminSettings("gavel-admin", "s3cret&="))
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun adminCalls() = seen.filterNot { it.path.endsWith("/token") }

    @Test
    fun `suspension disables the user and ends its sessions with a service-account token`() = runBlocking {
        admin.disableUser(subject)

        val login = seen.first()
        assertEquals("/realms/gavel/protocol/openid-connect/token", login.path)
        assertContains(login.body, "grant_type=client_credentials")
        assertContains(login.body, "client_id=gavel-admin")
        assertContains(login.body, "client_secret=s3cret%26%3D")

        val (disable, logout) = adminCalls()
        assertEquals("PUT" to "/admin/realms/gavel/users/$subject", disable.method to disable.path)
        assertEquals("""{"enabled":false}""", disable.body)
        assertEquals("POST" to "/admin/realms/gavel/users/$subject/logout", logout.method to logout.path)
        assertEquals("Bearer admin-token-1", disable.auth)
    }

    @Test
    fun `reactivation re-enables the user without touching sessions`() = runBlocking {
        admin.enableUser(subject)

        val call = adminCalls().single()
        assertEquals("PUT" to """{"enabled":true}""", call.method to call.body)
    }

    @Test
    fun `the service-account token is reused until it nears expiry`() = runBlocking {
        admin.disableUser(subject)
        admin.enableUser(subject)

        assertEquals(1, tokenCount)
    }

    @Test
    fun `a rejected admin call fails so the caller records the sync failure`() {
        adminStatus = 404
        val error = assertFailsWith<IdentityProviderAdminException> { runBlocking { admin.disableUser(subject) } }
        assertContains(error.message!!, "404")
    }

    @Test
    fun `a 401 drops the cached token so the next call logs in again`() = runBlocking {
        adminStatus = 401
        assertFailsWith<IdentityProviderAdminException> { admin.enableUser(subject) }
        adminStatus = 204
        admin.enableUser(subject)

        assertEquals(2, tokenCount)
    }

    @Test
    fun `a failed login is reported`() {
        tokenStatus = 401
        assertFailsWith<IdentityProviderAdminException> { runBlocking { admin.enableUser(subject) } }
    }

    @Test
    fun `a subject that is not a Keycloak user id is refused before any call`() {
        assertFailsWith<IllegalArgumentException> { runBlocking { admin.disableUser("../../realms") } }
        assertEquals(0, seen.size)
    }

    @Test
    fun `an issuer that is not a realm URL is rejected at startup`() {
        assertFailsWith<IllegalArgumentException> {
            KeycloakIdentityProviderAdmin("https://id.example.com/", IdentityProviderAdminSettings("a", "b"))
        }
    }
}
