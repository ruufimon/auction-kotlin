package com.gavel.kernel

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Random opaque tokens and their one-way hashes, for anything stored server-side. */
object Secrets {
    private val random = SecureRandom()

    fun randomToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
