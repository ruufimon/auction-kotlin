package com.gavel.onboarding.persistence

import com.gavel.kernel.testing.TestDatabase
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.AccountState
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.CreditPolicy
import com.gavel.onboarding.domain.Email
import com.gavel.onboarding.domain.IdentityClaims
import com.gavel.onboarding.domain.IdpSubject
import com.gavel.onboarding.domain.ProfilePatch
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIf
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #18: round-trip, unique subject, linking lookup, version conflict, encryption at rest. */
@EnabledIf("dockerAvailable")
class ExposedAccountRepositoryTest {
    companion object {
        @JvmStatic
        fun dockerAvailable() = TestDatabase.dockerAvailable
    }

    private lateinit var db: Database
    private val cipher = AesGcmFieldCipher(ByteArray(32) { 7 })
    private val repository = ExposedAccountRepository(cipher)
    private val t0 = Instant.parse("2026-11-07T10:00:00Z")
    private val policy = CreditPolicy("test-policy-1", 40, 100_000, 100_000, 40, Duration.ofDays(30))

    @BeforeEach
    fun setUp() {
        db = TestDatabase.migrate("classpath:db/migration/shared", "classpath:db/migration/onboarding")
        TestDatabase.truncate("users")
    }

    private fun claims(subject: String = "kc-${UUID.randomUUID()}", email: String = "mina@example.com", verified: Boolean = true) =
        IdentityClaims(IdpSubject(subject), Email.of(email), verified, "Mina")

    @Test
    fun `an account round-trips with profile, seller documents and assessment metadata`() {
        val created = Account.bootstrap(claims(), t0).account
        val assessed = created.assess(34, 6_800_000, 1_500_000, policy, t0.plusSeconds(10)).account
        val full = assessed.updateProfile(
            ProfilePatch(
                displayName = "Mina Kessler", phone = "+15035550142",
                shippingAddress = Address("418 NW Couch St", "Portland", "OR", "97209", "US"),
                sellerProfile = mapOf("legalName" to "Mina K", "storeName" to "Finds"),
                originAddress = mapOf("line1" to "1 Main", "country" to "US"),
            ),
            t0.plusSeconds(20),
        ).account

        transaction(db) {
            repository.insert(created)
            assertTrue(repository.update(assessed))
            assertTrue(repository.update(full))
        }

        val loaded = transaction(db) { repository.findBySubject(full.subject) }
        assertNotNull(loaded)
        // Inputs are never loaded back; everything else is equal.
        assertEquals(full.copy(assessment = full.assessment?.copy(inputs = null)), loaded)
        assertEquals(loaded, transaction(db) { repository.findByUserId(full.userId) })
    }

    @Test
    fun `the subject is unique`() {
        val a = Account.bootstrap(claims(subject = "kc-same", email = "a@example.com"), t0).account
        val b = Account.bootstrap(claims(subject = "kc-same", email = "b@example.com"), t0).account
        transaction(db) { repository.insert(a) }
        val error = assertFailsWith<ExposedSQLException> { transaction(db) { repository.insert(b) } }
        assertEquals("23505", error.sqlState)
    }

    @Test
    fun `linking lookup finds verified emails only`() {
        val unverified = Account.bootstrap(claims(email = "mina@example.com", verified = false), t0).account
        transaction(db) { repository.insert(unverified) }
        assertNull(transaction(db) { repository.findByVerifiedEmail(Email.of("mina@example.com")) })

        val verified = unverified.syncClaims(claims(subject = unverified.subject.value, verified = true), t0).account
        transaction(db) { repository.update(verified) }
        assertEquals(verified.userId, transaction(db) { repository.findByVerifiedEmail(Email.of("Mina@Example.com")) }?.userId)
    }

    @Test
    fun `a stale version is not written`() {
        val account = Account.bootstrap(claims(), t0).account
        transaction(db) { repository.insert(account) }
        val first = account.updateProfile(ProfilePatch(displayName = "First"), t0).account
        val second = account.updateProfile(ProfilePatch(displayName = "Second"), t0).account

        assertTrue(transaction(db) { repository.update(first) })
        assertFalse(transaction(db) { repository.update(second) }, "same base version must conflict")
        assertEquals("First", transaction(db) { repository.findByUserId(account.userId) }?.displayName)
    }

    @Test
    fun `assessment inputs are stored only as ciphertext`() {
        val account = Account.bootstrap(claims(), t0).account.assess(34, 6_800_000, 1_500_000, policy, t0).account
        transaction(db) { repository.insert(account) }

        val row = TestDatabase.query("SELECT users::text AS r, age_enc, annual_income_enc, net_wealth_enc FROM users") { rs ->
            rs.next(); listOf(rs.getString("r"), rs.getString("age_enc"), rs.getString("annual_income_enc"), rs.getString("net_wealth_enc"))
        }
        val (wholeRow, ageEnc, incomeEnc, wealthEnc) = row
        listOf("6800000", "1500000").forEach { assertFalse(wholeRow.contains(it), "plaintext $it found in row") }
        assertEquals("34", cipher.decrypt(ageEnc))
        assertEquals("6800000", cipher.decrypt(incomeEnc))
        assertEquals("1500000", cipher.decrypt(wealthEnc))
        assertNull(transaction(db) { repository.findBySubject(account.subject) }?.assessment?.inputs)
        assertEquals(AccountState.ACTIVE, transaction(db) { repository.findBySubject(account.subject) }?.state)
    }
}
