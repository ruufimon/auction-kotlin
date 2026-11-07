package com.gavel.user_onboarding.infrastructure

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

interface PasswordHasher {
    fun hash(plaintext: String): String
    fun verify(plaintext: String, hash: String): Boolean
}

class BcryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(12)

    override fun hash(plaintext: String): String {
        return encoder.encode(plaintext)
    }

    override fun verify(plaintext: String, hash: String): Boolean {
        return encoder.matches(plaintext, hash)
    }
}
