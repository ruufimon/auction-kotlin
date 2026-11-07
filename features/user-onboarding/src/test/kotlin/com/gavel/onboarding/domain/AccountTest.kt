package com.gavel.onboarding.domain

import com.gavel.onboarding.api.AccountCreatedV1
import com.gavel.onboarding.api.AccountReactivatedV1
import com.gavel.onboarding.api.AccountSuspendedV1
import com.gavel.onboarding.api.CreditEligibilityAssessedV1
import com.gavel.onboarding.api.EmailVerifiedV1
import com.gavel.onboarding.api.ProfileUpdatedV1
import com.gavel.onboarding.api.SellerEnabledV1
import org.junit.jupiter.api.Nested
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #17: every transition and invariant, with no Ktor, Exposed or Keycloak. */
class AccountTest {
    private val t0: Instant = Instant.parse("2026-11-07T10:00:00Z")
    private val policy = CreditPolicy(
        version = "test-policy-1", minimumScore = 40,
        incomeMinorPerPoint = 100_000, wealthMinorPerPoint = 100_000, maxPointsPerFactor = 40,
        retryAfter = Duration.ofDays(30),
    )

    private fun claims(email: String = "mina@example.com", verified: Boolean = false, name: String? = "Mina") =
        IdentityClaims(IdpSubject("kc-1"), Email.of(email), verified, name)

    private fun pendingEligibility(): Account = Account.bootstrap(claims(verified = true), t0).account
    private fun active(): Account = pendingEligibility().assess(34, 6_800_000, 1_500_000, policy, t0).account
    private val completeSeller = mapOf("legalName" to "Mina K", "storeName" to "Mina's Finds", "contactPhone" to "+15035550142", "payoutAccount" to "acct_1")
    private val completeOrigin = mapOf("line1" to "418 NW Couch St", "city" to "Portland", "region" to "OR", "postalCode" to "97209", "country" to "US")

    @Nested
    inner class Bootstrap {
        @Test
        fun `an unverified email enters PENDING_VERIFICATION and publishes AccountCreated`() {
            val change = Account.bootstrap(claims(verified = false), t0)

            assertEquals(AccountState.PENDING_VERIFICATION, change.account.state)
            assertFalse(change.account.emailVerified)
            val event = assertIs<AccountCreatedV1>(change.events.single())
            assertEquals(change.account.userId.value, event.userId)
            assertEquals("PENDING_VERIFICATION", event.accountState)
        }

        @Test
        fun `a verified email enters PENDING_CREDIT_ELIGIBILITY`() {
            val change = Account.bootstrap(claims(verified = true), t0)
            assertEquals(AccountState.PENDING_CREDIT_ELIGIBILITY, change.account.state)
            assertTrue(change.account.emailVerified)
        }

        @Test
        fun `the account copies email and display name from the token and is not a seller`() {
            val account = Account.bootstrap(claims(email = "  Mina@Example.COM ", name = "Mina"), t0).account
            assertEquals("mina@example.com", account.email.value)
            assertEquals("Mina", account.displayName)
            assertFalse(account.sellerEnabled)
            assertTrue(account.userId.value.startsWith("usr_"))
        }
    }

    @Nested
    inner class ClaimSync {
        @Test
        fun `email_verified on a pending account advances it and publishes EmailVerified`() {
            val account = Account.bootstrap(claims(verified = false), t0).account
            val change = account.syncClaims(claims(verified = true), t0.plusSeconds(60))

            assertEquals(AccountState.PENDING_CREDIT_ELIGIBILITY, change.account.state)
            assertTrue(change.account.emailVerified)
            assertIs<EmailVerifiedV1>(change.events.single())
            assertEquals(account.version + 1, change.account.version)
        }

        @Test
        fun `an unchanged token changes nothing`() {
            val account = Account.bootstrap(claims(verified = true), t0).account
            val change = account.syncClaims(claims(verified = true), t0.plusSeconds(60))
            assertFalse(change.changed)
            assertEquals(account, change.account)
        }

        @Test
        fun `a changed display name and email are refreshed without a state change`() {
            val account = active()
            val change = account.syncClaims(claims(email = "new@example.com", verified = true, name = "Mina K."), t0.plusSeconds(60))
            assertEquals("new@example.com", change.account.email.value)
            assertEquals("Mina K.", change.account.displayName)
            assertEquals(AccountState.ACTIVE, change.account.state)
            assertTrue(change.events.isEmpty())
        }

        @Test
        fun `verification never activates by itself`() {
            val change = Account.bootstrap(claims(verified = false), t0).account.syncClaims(claims(verified = true), t0)
            assertFalse(change.account.isActive)
        }
    }

    @Nested
    inner class Assessment {
        @Test
        fun `a passing assessment activates and records outcome, reason, policy version and time`() {
            val change = pendingEligibility().assess(34, 6_800_000, 1_500_000, policy, t0)

            assertEquals(AccountState.ACTIVE, change.account.state)
            val a = assertNotNull(change.account.assessment)
            assertEquals(EligibilityOutcome.ELIGIBLE, a.outcome)
            assertEquals("MEETS_POLICY", a.reason)
            assertEquals("test-policy-1", a.policyVersion)
            assertEquals(t0, a.assessedAt)
            val event = assertIs<CreditEligibilityAssessedV1>(change.events.single())
            assertEquals("ELIGIBLE", event.outcome)
            assertFalse(event.toString().contains("6800000"), "event must not carry inputs")
        }

        @Test
        fun `a failing assessment keeps the account pending with a stable reason`() {
            val change = pendingEligibility().assess(19, 900_000, 0, policy, t0)

            assertEquals(AccountState.PENDING_CREDIT_ELIGIBILITY, change.account.state)
            assertEquals(EligibilityOutcome.NOT_ELIGIBLE, change.account.assessment?.outcome)
            assertEquals("BELOW_MINIMUM_SCORE", change.account.assessment?.reason)
        }

        @Test
        fun `invalid inputs are rejected before scoring and name the field`() {
            val error = assertFailsWith<AccountError.ValidationFailed> { pendingEligibility().assess(17, -1, -250_000, policy, t0) }
            assertEquals(listOf("age", "annualIncome", "netWealth"), error.fields.map { it.field })
        }

        @Test
        fun `only PENDING_CREDIT_ELIGIBILITY accounts can be assessed`() {
            val pendingVerification = Account.bootstrap(claims(verified = false), t0).account
            val e1 = assertFailsWith<AccountError.NotAllowedInState> { pendingVerification.assess(34, 1, 1, policy, t0) }
            assertEquals("ACCOUNT_PENDING_VERIFICATION", e1.code)
            val e2 = assertFailsWith<AccountError.NotAllowedInState> { active().assess(34, 1, 1, policy, t0) }
            assertEquals("ACCOUNT_ACTIVE", e2.code)
        }

        @Test
        fun `a retry within the policy window is refused with the earliest retry time`() {
            val failed = pendingEligibility().assess(19, 900_000, 0, policy, t0).account
            val error = assertFailsWith<AccountError.AssessmentRetryNotYetAllowed> { failed.assess(34, 6_800_000, 1_500_000, policy, t0.plus(Duration.ofDays(1))) }
            assertEquals(t0.plus(Duration.ofDays(30)), error.earliestRetryAt)
        }

        @Test
        fun `a retry after the window is scored and may activate`() {
            val failed = pendingEligibility().assess(19, 900_000, 0, policy, t0).account
            val change = failed.assess(34, 6_800_000, 1_500_000, policy, t0.plus(Duration.ofDays(31)))
            assertEquals(AccountState.ACTIVE, change.account.state)
        }

        @Test
        fun `inputs never print`() {
            assertEquals("AssessmentInputs(redacted)", AssessmentInputs(34, 6_800_000, 1_500_000).toString())
        }
    }

    @Nested
    inner class Profile {
        @Test
        fun `updating fields bumps the version and publishes ProfileUpdated`() {
            val account = active()
            val change = account.updateProfile(ProfilePatch(displayName = "Mina Kessler", phone = "+15035550142"), t0.plusSeconds(5))

            assertEquals("Mina Kessler", change.account.displayName)
            assertEquals("+15035550142", change.account.phone)
            assertEquals(account.version + 1, change.account.version)
            assertIs<ProfileUpdatedV1>(change.events.single())
        }

        @Test
        fun `invalid values are rejected with field details`() {
            val error = assertFailsWith<AccountError.ValidationFailed> {
                active().updateProfile(ProfilePatch(displayName = "x".repeat(61), phone = "not-a-phone"), t0)
            }
            assertEquals(listOf("displayName", "phone"), error.fields.map { it.field })
            val blank = assertFailsWith<AccountError.ValidationFailed> { active().updateProfile(ProfilePatch(displayName = " "), t0) }
            assertEquals("REQUIRED", blank.fields.single().code)
        }

        @Test
        fun `a shipping address with a blank postal code names the field`() {
            val error = assertFailsWith<AccountError.ValidationFailed> {
                active().updateProfile(ProfilePatch(shippingAddress = Address("418 NW Couch St", "Portland", "OR", "", "US")), t0)
            }
            assertEquals("postalCode", error.fields.single().field)
        }

        @Test
        fun `an empty patch changes nothing`() {
            val account = active()
            assertFalse(account.updateProfile(ProfilePatch(), t0).changed)
        }
    }

    @Nested
    inner class Seller {
        @Test
        fun `an active account with every required field enables selling once`() {
            val account = active().updateProfile(ProfilePatch(sellerProfile = completeSeller, originAddress = completeOrigin), t0).account
            val change = account.enableSeller(SellerRequirements.DEFAULT, t0)

            assertTrue(change.account.sellerEnabled)
            assertEquals(t0, change.account.sellerEnabledAt)
            assertEquals(account.userId, change.account.userId)
            assertEquals("Mina's Finds", assertIs<SellerEnabledV1>(change.events.single()).storeName)
            assertFailsWith<AccountError.SellerAlreadyEnabled> { change.account.enableSeller(SellerRequirements.DEFAULT, t0) }
        }

        @Test
        fun `a missing seller field and a missing origin field are named`() {
            val account = active().updateProfile(
                ProfilePatch(sellerProfile = completeSeller - "payoutAccount", originAddress = completeOrigin - "postalCode"), t0,
            ).account
            val error = assertFailsWith<AccountError.SellerProfileIncomplete> { account.enableSeller(SellerRequirements.DEFAULT, t0) }
            assertEquals(listOf("payoutAccount", "originAddress.postalCode"), error.missingFields)
        }

        @Test
        fun `the required field set is configuration`() {
            val account = active().updateProfile(ProfilePatch(sellerProfile = completeSeller, originAddress = completeOrigin), t0).account
            val stricter = SellerRequirements(SellerRequirements.DEFAULT.sellerProfileFields + "taxId", SellerRequirements.DEFAULT.originAddressFields)
            val error = assertFailsWith<AccountError.SellerProfileIncomplete> { account.enableSeller(stricter, t0) }
            assertEquals(listOf("taxId"), error.missingFields)
        }

        @Test
        fun `accounts that are not active cannot enable selling`() {
            val pending = pendingEligibility().updateProfile(ProfilePatch(sellerProfile = completeSeller, originAddress = completeOrigin), t0).account
            val error = assertFailsWith<AccountError.NotAllowedInState> { pending.enableSeller(SellerRequirements.DEFAULT, t0) }
            assertEquals("ACCOUNT_PENDING_CREDIT_ELIGIBILITY", error.code)
        }
    }

    @Nested
    inner class Suspension {
        @Test
        fun `suspension is allowed from every pre-suspension state and records the reason`() {
            val states = listOf(Account.bootstrap(claims(verified = false), t0).account, pendingEligibility(), active())
            states.forEach { account ->
                val change = account.suspend("FRAUD_REVIEW", t0)
                assertEquals(AccountState.SUSPENDED, change.account.state)
                assertEquals("FRAUD_REVIEW", assertIs<AccountSuspendedV1>(change.events.single()).reason)
                assertFailsWith<AccountError.AlreadySuspended> { change.account.suspend("again", t0) }
            }
        }

        @Test
        fun `a suspended account refuses active-only operations with ACCOUNT_SUSPENDED`() {
            val suspended = active().suspend("FRAUD_REVIEW", t0).account
            val error = assertFailsWith<AccountError.NotAllowedInState> { suspended.requireActive("bid") }
            assertEquals("ACCOUNT_SUSPENDED", error.code)
        }

        @Test
        fun `reactivation requires verified email and passed eligibility`() {
            val suspendedPending = pendingEligibility().suspend("x", t0).account
            val error = assertFailsWith<AccountError.ReactivationPreconditionsNotMet> { suspendedPending.reactivate(t0) }
            assertEquals(listOf("passed credit-eligibility assessment"), error.missing)

            val reactivated = active().suspend("x", t0).account.reactivate(t0.plusSeconds(1))
            assertEquals(AccountState.ACTIVE, reactivated.account.state)
            assertNull(reactivated.account.suspendedAt)
            assertIs<AccountReactivatedV1>(reactivated.events.single())
        }

        @Test
        fun `only suspended accounts can be reactivated`() {
            assertFailsWith<AccountError.NotSuspended> { active().reactivate(t0) }
        }
    }
}
