package com.gavel.kernel.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccessTokenValidatorTest {
    private val now: Instant = Instant.parse("2026-11-07T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val idp = TestIdentityProvider(clock = clock)
    private val validator = AccessTokenValidator(idp.issuer, idp.audience, idp.jwkProvider, clock)

    @Test
    fun `a token signed by the realm with the right issuer and audience is accepted with its claims`() {
        val token = idp.mint(subject = "kc-1", email = "mina@example.com", emailVerified = false, name = "Mina")

        val result = validator.validate(token)

        val accepted = assertIs<TokenResult.Accepted>(result)
        assertEquals("kc-1", accepted.principal.subject)
        assertEquals("mina@example.com", accepted.principal.email)
        assertEquals(false, accepted.principal.emailVerified)
        assertEquals("Mina", accepted.principal.displayName)
    }

    @Test
    fun `a token signed by a key the realm does not publish is TOKEN_INVALID`() {
        val token = idp.mint(signingKey = idp.foreignKey(), keyId = "unknown-kid")
        assertRejected(token, TokenErrorCodes.INVALID)
    }

    @Test
    fun `a token signed with the realm key but claiming the same kid from another key is TOKEN_INVALID`() {
        val token = idp.mint(signingKey = idp.foreignKey())
        assertRejected(token, TokenErrorCodes.INVALID)
    }

    @Test
    fun `a token from a different issuer is TOKEN_INVALID`() {
        assertRejected(idp.mint(issuer = "https://evil.test/realms/gavel"), TokenErrorCodes.INVALID)
    }

    @Test
    fun `a token for another audience is TOKEN_WRONG_AUDIENCE`() {
        assertRejected(idp.mint(audience = listOf("account")), TokenErrorCodes.WRONG_AUDIENCE)
    }

    @Test
    fun `a token that expired 16 minutes ago is TOKEN_EXPIRED`() {
        val token = idp.mint(issuedAt = now.minus(Duration.ofMinutes(31)), ttl = Duration.ofMinutes(15))
        assertRejected(token, TokenErrorCodes.EXPIRED)
    }

    @Test
    fun `a token expired within the leeway is still accepted`() {
        val token = idp.mint(issuedAt = now.minus(Duration.ofSeconds(15 * 60 + 10)), ttl = Duration.ofMinutes(15))
        assertIs<TokenResult.Accepted>(validator.validate(token))
    }

    @Test
    fun `garbage is TOKEN_INVALID`() {
        assertRejected("not.a.jwt", TokenErrorCodes.INVALID)
    }

    private fun assertRejected(token: String, code: String) {
        val result = validator.validate(token)
        val rejected = assertIs<TokenResult.Rejected>(result)
        assertEquals(code, rejected.code)
        assertTrue(rejected.message.isNotBlank())
    }
}
