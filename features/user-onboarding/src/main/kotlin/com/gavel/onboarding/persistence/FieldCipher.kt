package com.gavel.onboarding.persistence

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts individual sensitive values before they reach a database column (USR-012). */
interface FieldCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * AES-256-GCM with a fresh 96-bit nonce per value; output is base64(nonce || ciphertext+tag).
 * Equal plaintexts never produce equal ciphertexts, so columns cannot be compared or indexed.
 */
class AesGcmFieldCipher(key: ByteArray) : FieldCipher {
    init { require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" } }

    private val keySpec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce)) }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        require(bytes.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = bytes.copyOfRange(0, NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce)) }
        return String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        /** Key material from configuration: standard base64 of 32 random bytes. */
        fun fromBase64Key(base64: String): AesGcmFieldCipher = AesGcmFieldCipher(Base64.getDecoder().decode(base64.trim()))
    }
}
