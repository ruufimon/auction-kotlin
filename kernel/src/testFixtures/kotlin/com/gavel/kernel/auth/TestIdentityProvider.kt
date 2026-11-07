package com.gavel.kernel.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Stands in for a Keycloak realm in tests: owns a signing key, publishes it as a
 * [JwkProvider], and mints access tokens with whatever claims a scenario needs.
 * Tokens still pass through the real [AccessTokenValidator]; the only seam is
 * which key set the validator trusts (acceptance README, "Testing with and without Keycloak").
 */
class TestIdentityProvider(
    val issuer: String = "https://keycloak.test/realms/gavel",
    val audience: String = IdentityProviderSettings.DEFAULT_AUDIENCE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val keyId: String = UUID.randomUUID().toString()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    /** Key set the application is configured to trust. */
    val jwkProvider: JwkProvider = InMemoryJwkProvider(mapOf(keyId to publicKey))

    data class User(val subject: String, val email: String, val emailVerified: Boolean, val displayName: String?)

    private val users = linkedMapOf<String, User>()

    /** Email-and-password registration at the provider: email starts unverified. */
    fun registerPasswordUser(email: String, displayName: String? = null): User =
        upsert(User("kc-" + UUID.randomUUID(), email.trim().lowercase(), emailVerified = false, displayName = displayName))

    /** Google brokered identity: Keycloak trusts Google's verified email. */
    fun registerGoogleUser(email: String, displayName: String? = null): User =
        upsert(User("kc-" + UUID.randomUUID(), email.trim().lowercase(), emailVerified = true, displayName = displayName))

    fun markEmailVerified(email: String): User {
        val u = user(email)
        return upsert(u.copy(emailVerified = true))
    }

    fun user(email: String): User = users[email.trim().lowercase()] ?: error("no provider user for $email")
    fun userOrNull(email: String): User? = users[email.trim().lowercase()]
    fun usersWithEmail(email: String): Int = users.values.count { it.email == email.trim().lowercase() }

    private fun upsert(u: User): User { users[u.email] = u; return u }

    fun tokenFor(email: String, ttl: Duration = Duration.ofMinutes(15)): String = tokenFor(user(email), ttl)

    fun tokenFor(user: User, ttl: Duration = Duration.ofMinutes(15)): String = mint(
        subject = user.subject, email = user.email, emailVerified = user.emailVerified,
        name = user.displayName, ttl = ttl,
    )

    /** Full control for negative cases: wrong issuer, audience, expiry, other key. */
    fun mint(
        subject: String = "kc-" + UUID.randomUUID(),
        email: String? = "someone@example.com",
        emailVerified: Boolean = true,
        name: String? = null,
        issuer: String = this.issuer,
        audience: List<String> = listOf(this.audience),
        issuedAt: Instant = clock.instant(),
        ttl: Duration = Duration.ofMinutes(15),
        signingKey: RSAPrivateKey = privateKey,
        keyId: String = this.keyId,
    ): String {
        val builder = JWT.create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withSubject(subject)
            .withAudience(*audience.toTypedArray())
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plus(ttl)))
            .withClaim("email_verified", emailVerified)
        if (email != null) builder.withClaim("email", email)
        if (name != null) builder.withClaim("name", name)
        return builder.sign(Algorithm.RSA256(null, signingKey))
    }

    /** A key the realm never published, for the "signed by another key" case. */
    fun foreignKey(): RSAPrivateKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateKey
}

class InMemoryJwkProvider(private val keys: Map<String, RSAPublicKey>) : JwkProvider {
    override fun get(keyId: String?): Jwk {
        val key = keys[keyId] ?: throw SigningKeyNotFoundException("No key with id $keyId", null)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return Jwk.fromValues(
            mapOf(
                "kid" to keyId, "kty" to "RSA", "alg" to "RS256", "use" to "sig",
                "n" to b64.encodeToString(key.modulus.toByteArray()),
                "e" to b64.encodeToString(key.publicExponent.toByteArray()),
            ),
        )
    }
}
