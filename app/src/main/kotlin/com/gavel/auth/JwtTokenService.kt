package com.gavel.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.*

data class JwtClaims(
    val userId: String,
    val email: String,
    val issuedAt: Instant,
    val expiresAt: Instant
)

interface TokenService {
    fun issueAccessToken(userId: String, email: String): String
    fun validateAccessToken(token: String): JwtClaims?
}

class JwtTokenService : TokenService {
    private val secret = System.getenv("JWT_SECRET") ?: "dev-secret-key-change-in-production"
    private val issuer = System.getenv("JWT_ISSUER") ?: "auction-api"
    private val audience = System.getenv("JWT_AUDIENCE") ?: "auction-client"
    private val accessTokenTtlSeconds = System.getenv("JWT_ACCESS_TOKEN_TTL")?.toLongOrNull() ?: 900L  // 15 minutes

    private val algorithm = Algorithm.HMAC256(secret)

    override fun issueAccessToken(userId: String, email: String): String {
        val now = Instant.now()
        val expiresAt = Date(now.toEpochMilli() + accessTokenTtlSeconds * 1000)

        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("user_id", userId)
            .withClaim("email", email)
            .withIssuedAt(Date(now.toEpochMilli()))
            .withExpiresAt(expiresAt)
            .sign(algorithm)
    }

    override fun validateAccessToken(token: String): JwtClaims? {
        return try {
            val verifier = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()

            val decodedJWT = verifier.verify(token)
            val userId = decodedJWT.getClaim("user_id").asString()
            val email = decodedJWT.getClaim("email").asString()
            val issuedAt = Instant.ofEpochMilli(decodedJWT.issuedAt.time)
            val expiresAt = Instant.ofEpochMilli(decodedJWT.expiresAt.time)

            JwtClaims(userId, email, issuedAt, expiresAt)
        } catch (e: Exception) {
            null
        }
    }
}
