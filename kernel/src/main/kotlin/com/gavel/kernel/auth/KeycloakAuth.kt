package com.gavel.kernel.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.gavel.kernel.web.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.response.header
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Stable 401 codes (acceptance: sign-in.feature). */
object TokenErrorCodes {
    const val MISSING = "TOKEN_MISSING"
    const val INVALID = "TOKEN_INVALID"
    const val EXPIRED = "TOKEN_EXPIRED"
    const val WRONG_AUDIENCE = "TOKEN_WRONG_AUDIENCE"
}

class KeycloakAuthConfig internal constructor(name: String?) : AuthenticationProvider.Config(name) {
    lateinit var issuer: String
    var audience: String = IdentityProviderSettings.DEFAULT_AUDIENCE

    /** Source of the realm's public keys. Production: [cachedJwkProvider]; tests: an in-memory one. */
    lateinit var jwkProvider: JwkProvider
    var clock: Clock = Clock.systemUTC()
    var leewaySeconds: Long = 30
    var meterRegistry: MeterRegistry? = null

    fun settings(settings: IdentityProviderSettings) {
        issuer = settings.issuer
        audience = settings.audience
        jwkProvider = cachedJwkProvider(settings.jwksUrl)
    }
}

/** JWKS fetched over HTTP, cached by key id, refetched (rate limited) on unknown ids: handles rotation. */
fun cachedJwkProvider(jwksUrl: String): JwkProvider =
    JwkProviderBuilder(URL(jwksUrl))
        .cached(16, 10, TimeUnit.MINUTES)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

/**
 * Bearer-token authentication against the identity provider's published keys
 * (USR-005). The only trust seam is [KeycloakAuthConfig.jwkProvider]; nothing in the
 * application can mint a token the validator accepts without the realm's private key.
 */
fun AuthenticationConfig.keycloak(name: String? = null, configure: KeycloakAuthConfig.() -> Unit) {
    val config = KeycloakAuthConfig(name).apply(configure)
    register(KeycloakAuthProvider(config))
}

class KeycloakAuthProvider internal constructor(private val config: KeycloakAuthConfig) : AuthenticationProvider(config) {
    private val log = LoggerFactory.getLogger(KeycloakAuthProvider::class.java)
    private val validator = AccessTokenValidator(config.issuer, config.audience, config.jwkProvider, config.clock, config.leewaySeconds)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val header = try { call.request.parseAuthorizationHeader() } catch (e: IllegalArgumentException) { null }
        val token = (header as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob

        val result = if (token == null) TokenResult.Rejected(TokenErrorCodes.MISSING, "Bearer token required")
        else withContext(Dispatchers.IO) { validator.validate(token) }

        when (result) {
            is TokenResult.Accepted -> context.principal(name, result.principal)
            is TokenResult.Rejected -> {
                config.meterRegistry?.counter("auth.token.rejected", "code", result.code)?.increment()
                log.debug("Token rejected: {}", result.code)
                context.challenge(CHALLENGE_KEY, AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                    c.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"gavel\", error=\"invalid_token\"")
                    c.respondError(HttpStatusCode.Unauthorized, result.code, result.message)
                    challenge.complete()
                }
            }
        }
    }

    private companion object {
        const val CHALLENGE_KEY = "KeycloakAuth"
    }
}

sealed interface TokenResult {
    data class Accepted(val principal: TokenPrincipal) : TokenResult
    data class Rejected(val code: String, val message: String) : TokenResult
}

/** Pure verification logic, testable without Ktor. */
class AccessTokenValidator(
    private val issuer: String,
    private val audience: String,
    private val jwkProvider: JwkProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val leewaySeconds: Long = 30,
) {
    fun validate(token: String): TokenResult {
        val decoded: DecodedJWT = try {
            JWT.decode(token)
        } catch (e: JWTDecodeException) {
            return TokenResult.Rejected(TokenErrorCodes.INVALID, "Token could not be decoded")
        }
        val publicKey = try {
            jwkProvider.get(decoded.keyId).publicKey as? RSAPublicKey
                ?: return TokenResult.Rejected(TokenErrorCodes.INVALID, "Unsupported signing key")
        } catch (e: JwkException) {
            return TokenResult.Rejected(TokenErrorCodes.INVALID, "Unknown signing key")
        }
        val verifier = (JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(issuer)
            .acceptLeeway(leewaySeconds) as JWTVerifier.BaseVerification)
            .build(clock)
        val verified = try {
            verifier.verify(decoded)
        } catch (e: TokenExpiredException) {
            return TokenResult.Rejected(TokenErrorCodes.EXPIRED, "Token has expired")
        } catch (e: JWTVerificationException) {
            return TokenResult.Rejected(TokenErrorCodes.INVALID, "Token signature or issuer is not valid")
        }
        if (verified.audience?.contains(audience) != true) {
            return TokenResult.Rejected(TokenErrorCodes.WRONG_AUDIENCE, "Token is not intended for this API")
        }
        val subject = verified.subject?.takeIf { it.isNotBlank() }
            ?: return TokenResult.Rejected(TokenErrorCodes.INVALID, "Token has no subject")
        return TokenResult.Accepted(
            TokenPrincipal(
                subject = subject,
                email = verified.getClaim("email").asString(),
                emailVerified = verified.getClaim("email_verified").asBoolean() ?: false,
                displayName = verified.getClaim("name").asString()
                    ?: verified.getClaim("preferred_username").asString(),
                expiresAt = verified.expiresAtAsInstant ?: clock.instant(),
            ),
        )
    }
}

/** Trusts no key at all: every bearer token is rejected. For applications started without a realm. */
object NoKeysJwkProvider : JwkProvider {
    override fun get(keyId: String?): com.auth0.jwk.Jwk = throw com.auth0.jwk.SigningKeyNotFoundException("No identity provider configured", null)
}
