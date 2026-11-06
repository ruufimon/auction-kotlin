package com.gavel.onboarding.infrastructure

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.gavel.kernel.Secrets
import com.gavel.kernel.UserId
import com.gavel.onboarding.application.AccessTokens
import com.gavel.onboarding.application.EmailSender
import com.gavel.onboarding.application.PasswordHasher
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date

/** Argon2id (memory-hard) hashes in PHC string format. Defaults follow OWASP guidance. */
class Argon2PasswordHasher(
    private val memoryKiB: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
) : PasswordHasher {
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    override fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt, memoryKiB, iterations, parallelism)
        return "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    override fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = b64d.decode(parts[4])
        val expected = b64d.decode(parts[5])
        val actual = derive(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, m: Int, t: Int, p: Int, length: Int = 32): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m).withIterations(t).withParallelism(p).withSalt(salt)
                .build(),
        )
        return ByteArray(length).also { generator.generateBytes(password.toCharArray(), it) }
    }
}

data class JwtSettings(val secret: String, val issuer: String = "gavel", val audience: String = "gavel-api", val ttlSeconds: Long = 900) {
    init {
        require(secret.length >= 32) { "JWT secret must be at least 32 characters" }
    }
}

class JwtAccessTokens(private val settings: JwtSettings, clock: java.time.Clock) : AccessTokens {
    private val algorithm = Algorithm.HMAC256(settings.secret)

    val verifier: JWTVerifier = (JWT.require(algorithm).withIssuer(settings.issuer).withAudience(settings.audience) as com.auth0.jwt.JWTVerifier.BaseVerification)
        .build(clock)

    override val ttlSeconds get() = settings.ttlSeconds

    override fun issue(userId: UserId, now: Instant): String =
        JWT.create()
            .withIssuer(settings.issuer)
            .withAudience(settings.audience)
            .withSubject(userId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(settings.ttlSeconds)))
            .withJWTId(Secrets.randomToken(12))
            .sign(algorithm)
}

/**
 * Development email delivery: logs that a message was sent without the
 * secret link. Use [CapturingEmailSender] in tests and a real provider in production.
 */
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun sendVerification(to: String, displayName: String, verificationLink: String) {
        log.info("Verification email queued for delivery (recipient redacted, link redacted)")
    }
}

/**
 * Local development only: logs the verification link so a developer without an
 * email provider can finish sign-up. The app refuses to enable it with secure cookies.
 */
class DevelopmentLinkEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(DevelopmentLinkEmailSender::class.java)

    override fun sendVerification(to: String, displayName: String, verificationLink: String) {
        log.warn("DEVELOPMENT ONLY - verification link: {}", verificationLink)
    }
}

/** Keeps sent links in memory so tests and local tooling can complete verification. */
class CapturingEmailSender : EmailSender {
    private val links = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun sendVerification(to: String, displayName: String, verificationLink: String) {
        links[to] = verificationLink
    }

    fun lastLinkFor(email: String): String? = links[email.lowercase()]
    fun lastTokenFor(email: String): String? = lastLinkFor(email)?.substringAfter("token=")
}
