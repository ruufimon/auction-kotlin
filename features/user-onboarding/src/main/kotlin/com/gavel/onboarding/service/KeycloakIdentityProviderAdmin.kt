package com.gavel.onboarding.service

import com.gavel.onboarding.config.IdentityProviderAdminSettings
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Keycloak admin-API implementation of [IdentityProviderAdmin] (USR-010). Logs in as the
 * realm's `gavel-admin` service account (client credentials, `manage-users`) and acts on the
 * user whose id is the token subject.
 *
 * @param issuer the realm URL, e.g. `https://id.example.com/realms/gavel`; the token and admin
 * endpoints are derived from it.
 */
class KeycloakIdentityProviderAdmin(
    issuer: String,
    private val settings: IdentityProviderAdminSettings,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val clock: Clock = Clock.systemUTC(),
) : IdentityProviderAdmin {
    private val tokenUrl: URI
    private val usersUrl: String

    init {
        val realmUrl = issuer.trimEnd('/')
        val marker = realmUrl.lastIndexOf("/realms/")
        require(marker >= 0) { "OIDC issuer '$issuer' is not a Keycloak realm URL (…/realms/<name>)" }
        tokenUrl = URI.create("$realmUrl/protocol/openid-connect/token")
        usersUrl = realmUrl.substring(0, marker) + "/admin" + realmUrl.substring(marker) + "/users"
    }

    private val lock = Mutex()
    private var token: String? = null
    private var tokenExpiresAt: Instant = Instant.EPOCH

    /** Disables the user, then ends every session so neither sign-in nor refresh works. */
    override suspend fun disableUser(subject: String) {
        setEnabled(subject, false)
        send(adminRequest("${userUrl(subject)}/logout").POST(HttpRequest.BodyPublishers.noBody()), "logout")
    }

    /** Re-enables sign-in; sessions ended at suspension stay ended. */
    override suspend fun enableUser(subject: String) = setEnabled(subject, true)

    private suspend fun setEnabled(subject: String, enabled: Boolean) {
        val request = adminRequest(userUrl(subject))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("""{"enabled":$enabled}"""))
        send(request, if (enabled) "enable user" else "disable user")
    }

    private fun userUrl(subject: String): String {
        // Keycloak user ids are UUIDs; anything else must not be spliced into a URL path.
        require(SUBJECT.matches(subject)) { "Unexpected subject format" }
        return "$usersUrl/$subject"
    }

    private suspend fun adminRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer ${accessToken()}")

    private suspend fun send(builder: HttpRequest.Builder, action: String) {
        val response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding()).await()
        if (response.statusCode() == 401) {
            // A token revoked early (e.g. a rotated client secret); log in again once.
            lock.withLock { token = null }
        }
        if (response.statusCode() !in 200..299) {
            throw IdentityProviderAdminException("Keycloak $action failed with HTTP ${response.statusCode()}")
        }
    }

    private suspend fun accessToken(): String = lock.withLock {
        val cached = token
        if (cached != null && clock.instant().isBefore(tokenExpiresAt)) return@withLock cached
        val form = "grant_type=client_credentials" +
            "&client_id=" + URLEncoder.encode(settings.clientId, Charsets.UTF_8) +
            "&client_secret=" + URLEncoder.encode(settings.clientSecret, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(tokenUrl)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() != 200) {
            throw IdentityProviderAdminException("Keycloak admin login failed with HTTP ${response.statusCode()}")
        }
        val body = Json.parseToJsonElement(response.body()).jsonObject
        val fresh = body["access_token"]?.jsonPrimitive?.content
            ?: throw IdentityProviderAdminException("Keycloak admin login returned no access_token")
        val lifetime = body["expires_in"]?.jsonPrimitive?.long ?: 60
        token = fresh
        // Renew a little early so a request never carries a token that expires in flight.
        tokenExpiresAt = clock.instant().plusSeconds((lifetime - EXPIRY_MARGIN_SECONDS).coerceAtLeast(0))
        fresh
    }

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 30L
        val SUBJECT = Regex("^[0-9a-fA-F-]{36}$")
    }
}

class IdentityProviderAdminException(message: String) : RuntimeException(message)
