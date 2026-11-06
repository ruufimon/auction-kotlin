package com.gavel.onboarding

import com.gavel.onboarding.domain.PasswordPolicy
import com.gavel.onboarding.infrastructure.Argon2PasswordHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OnboardingRulesTest {
    @Test
    fun `password policy enforces length and character mix`() {
        val policy = PasswordPolicy(minLength = 10)
        assertEquals(listOf("TOO_SHORT", "TOO_WEAK"), policy.violations("abc").map { it.code })
        assertEquals(listOf("TOO_WEAK"), policy.violations("abcdefghijk").map { it.code })
        assertTrue(policy.violations("abcdefghi1").isEmpty())
    }

    @Test
    fun `argon2id hashes are salted and verifiable`() {
        val hasher = Argon2PasswordHasher(memoryKiB = 1024, iterations = 1)
        val a = hasher.hash("Password123")
        val b = hasher.hash("Password123")
        assertTrue(a.startsWith("\$argon2id\$v=19\$m=1024,t=1,p=1\$"))
        assertNotEquals(a, b)
        assertTrue(hasher.verify("Password123", a))
        assertFalse(hasher.verify("Password124", a))
        assertFalse(hasher.verify("Password123", "garbage"))
    }
}
