package com.gavel.onboarding.domain

import java.security.SecureRandom

/** Identity-provider subject (`sub` claim): the stable key of an account (USR-013). */
@JvmInline
value class IdpSubject(val value: String) {
    init { require(value.isNotBlank()) { "subject must not be blank" } }
}

/** Opaque public user id, `usr_` + 20 Crockford-base32 characters; never sequential. */
@JvmInline
value class UserId(val value: String) {
    init { require(value.startsWith(PREFIX) && value.length == PREFIX.length + LENGTH) { "malformed user id" } }

    companion object {
        const val PREFIX = "usr_"
        const val LENGTH = 20
        private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
        private val random = SecureRandom()

        fun generate(): UserId = UserId(PREFIX + buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } })

        fun parseOrNull(raw: String): UserId? = runCatching { UserId(raw) }.getOrNull()
    }
}

/** Normalized email: trimmed and lower-cased, so lookups and uniqueness ignore case (USR-001). */
@JvmInline
value class Email private constructor(val value: String) {
    companion object {
        private val SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun of(raw: String): Email {
            val normalized = raw.trim().lowercase()
            require(SHAPE.matches(normalized)) { "malformed email" }
            return Email(normalized)
        }

        fun ofOrNull(raw: String?): Email? = raw?.let { runCatching { of(it) }.getOrNull() }
    }
}
