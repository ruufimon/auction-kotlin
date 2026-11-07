package com.gavel.onboarding.persistence

import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AesGcmFieldCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cipher = AesGcmFieldCipher(key)

    @Test
    fun `round-trips and never emits the plaintext`() {
        val encrypted = cipher.encrypt("6800000")
        assertFalse(encrypted.contains("6800000"))
        assertEquals("6800000", cipher.decrypt(encrypted))
    }

    @Test
    fun `equal values encrypt differently so columns cannot be compared`() {
        assertNotEquals(cipher.encrypt("34"), cipher.encrypt("34"))
    }

    @Test
    fun `a different key cannot decrypt`() {
        val other = AesGcmFieldCipher(ByteArray(32) { (it + 1).toByte() })
        assertFailsWith<AEADBadTagException> { other.decrypt(cipher.encrypt("34")) }
    }

    @Test
    fun `keys come from base64 and must be 32 bytes`() {
        AesGcmFieldCipher.fromBase64Key(Base64.getEncoder().encodeToString(key))
        assertFailsWith<IllegalArgumentException> { AesGcmFieldCipher(ByteArray(16)) }
    }
}
