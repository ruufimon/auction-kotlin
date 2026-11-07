package com.gavel.onboarding.domain

import com.gavel.onboarding.api.AccountCreatedV1
import com.gavel.onboarding.api.AccountEvent
import com.gavel.onboarding.api.AccountReactivatedV1
import com.gavel.onboarding.api.AccountSuspendedV1
import com.gavel.onboarding.api.CreditEligibilityAssessedV1
import com.gavel.onboarding.api.EmailVerifiedV1
import com.gavel.onboarding.api.ProfileUpdatedV1
import com.gavel.onboarding.api.SellerEnabledV1
import java.time.Instant
import java.util.UUID

/** Claims the application copies from a validated token (USR-013). */
data class IdentityClaims(
    val subject: IdpSubject,
    val email: Email,
    val emailVerified: Boolean,
    val displayName: String?,
)

data class Address(val line1: String, val city: String, val region: String, val postalCode: String, val country: String)

/** Result of a scored assessment. [inputs] is present only until persisted (encrypted) and never loaded back. */
data class CreditAssessment(
    val outcome: EligibilityOutcome,
    val reason: String,
    val policyVersion: String,
    val assessedAt: Instant,
    val inputs: AssessmentInputs? = null,
) {
    val passed: Boolean get() = outcome == EligibilityOutcome.ELIGIBLE
}

/** Fields a user may change through the profile endpoint (USR-007). `null` means "leave unchanged". */
data class ProfilePatch(
    val displayName: String? = null,
    val phone: String? = null,
    val shippingAddress: Address? = null,
    val sellerProfile: Map<String, String>? = null,
    val originAddress: Map<String, String>? = null,
) {
    val isEmpty: Boolean get() = displayName == null && phone == null && shippingAddress == null && sellerProfile == null && originAddress == null
}

/** An aggregate operation yields the new account plus the events to publish with it. */
data class AccountChange(val account: Account, val events: List<AccountEvent>) {
    val changed: Boolean get() = events.isNotEmpty()
}

/**
 * The application-side account (USR-013). Keycloak owns credentials and tokens; this
 * aggregate owns state, eligibility, profile and seller status. Immutable: every
 * operation returns an [AccountChange]; invariants are enforced here, not in routes.
 */
data class Account(
    val id: UUID,
    val userId: UserId,
    val subject: IdpSubject,
    val email: Email,
    val emailVerified: Boolean,
    val displayName: String?,
    val state: AccountState,
    val phone: String? = null,
    val shippingAddress: Address? = null,
    val sellerProfile: Map<String, String> = emptyMap(),
    val originAddress: Map<String, String> = emptyMap(),
    val sellerEnabled: Boolean = false,
    val sellerEnabledAt: Instant? = null,
    val assessment: CreditAssessment? = null,
    val suspendedAt: Instant? = null,
    val suspensionReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Optimistic-concurrency version; incremented on every persisted change. */
    val version: Int = 1,
) {
    val isActive: Boolean get() = state == AccountState.ACTIVE

    /** Refuses unless the account is [AccountState.ACTIVE]; the refusal carries the state's stable code. */
    fun requireActive(operation: String) {
        if (state != AccountState.ACTIVE) throw AccountError.NotAllowedInState(state, operation)
    }

    /**
     * Applies what the provider says on this request: a verified email advances a
     * PENDING_VERIFICATION account; email and display name follow the token (USR-013).
     */
    fun syncClaims(claims: IdentityClaims, now: Instant): AccountChange {
        var next = this
        val events = mutableListOf<AccountEvent>()
        if (claims.emailVerified && !emailVerified) {
            next = next.copy(emailVerified = true)
        }
        if (claims.emailVerified && next.state == AccountState.PENDING_VERIFICATION) {
            next = next.copy(state = AccountState.PENDING_CREDIT_ELIGIBILITY)
            events += EmailVerifiedV1(userId.value, claims.email.value, now.toString())
        }
        if (claims.email != email) next = next.copy(email = claims.email)
        if (claims.displayName != null && claims.displayName != displayName) next = next.copy(displayName = claims.displayName)
        return if (next == this) AccountChange(this, emptyList()) else AccountChange(next.touched(now), events)
    }

    fun updateProfile(patch: ProfilePatch, now: Instant): AccountChange {
        val errors = validateProfile(patch)
        if (errors.isNotEmpty()) throw AccountError.ValidationFailed(errors)
        if (patch.isEmpty) return AccountChange(this, emptyList())
        val next = copy(
            displayName = patch.displayName ?: displayName,
            phone = patch.phone ?: phone,
            shippingAddress = patch.shippingAddress ?: shippingAddress,
            sellerProfile = patch.sellerProfile ?: sellerProfile,
            originAddress = patch.originAddress ?: originAddress,
        ).touched(now)
        return AccountChange(next, listOf(ProfileUpdatedV1(userId.value, next.version, now.toString())))
    }

    /**
     * Scores the inputs with [policy] (USR-012). Only PENDING_CREDIT_ELIGIBILITY accounts may be
     * assessed; a failed assessment blocks retries until [CreditPolicy.retryAfter] has passed.
     * The returned change is persisted for both outcomes; callers raise
     * [AccountError.CreditEligibilityNotMet] after committing a failed one.
     */
    fun assess(age: Int, annualIncomeMinor: Long, netWealthMinor: Long, policy: CreditPolicy, now: Instant): AccountChange {
        if (state != AccountState.PENDING_CREDIT_ELIGIBILITY) throw AccountError.NotAllowedInState(state, "credit-eligibility assessment")
        assessment?.takeIf { !it.passed }?.let { previous ->
            val earliest = previous.assessedAt.plus(policy.retryAfter)
            if (now.isBefore(earliest)) throw AccountError.AssessmentRetryNotYetAllowed(earliest)
        }
        val errors = AssessmentInputs.validate(age, annualIncomeMinor, netWealthMinor)
        if (errors.isNotEmpty()) throw AccountError.ValidationFailed(errors)
        val inputs = AssessmentInputs(age, annualIncomeMinor, netWealthMinor)
        val evaluation = policy.evaluate(inputs)
        val result = CreditAssessment(evaluation.outcome, evaluation.reason, policy.version, now, inputs)
        val next = copy(
            assessment = result,
            state = if (result.passed) AccountState.ACTIVE else state,
        ).touched(now)
        val event = CreditEligibilityAssessedV1(userId.value, result.outcome.name, result.reason, policy.version, now.toString())
        return AccountChange(next, listOf(event))
    }

    fun enableSeller(requirements: SellerRequirements, now: Instant): AccountChange {
        requireActive("enable-seller")
        if (sellerEnabled) throw AccountError.SellerAlreadyEnabled()
        val missing = requirements.missingIn(sellerProfile, originAddress)
        if (missing.isNotEmpty()) throw AccountError.SellerProfileIncomplete(missing)
        val next = copy(sellerEnabled = true, sellerEnabledAt = now).touched(now)
        return AccountChange(next, listOf(SellerEnabledV1(userId.value, sellerProfile["storeName"], now.toString())))
    }

    fun suspend(reason: String, now: Instant): AccountChange {
        if (state == AccountState.SUSPENDED) throw AccountError.AlreadySuspended()
        require(reason.isNotBlank()) { "suspension reason required" }
        val next = copy(state = AccountState.SUSPENDED, suspendedAt = now, suspensionReason = reason).touched(now)
        return AccountChange(next, listOf(AccountSuspendedV1(userId.value, now.toString(), reason)))
    }

    /** Operator action; allowed only when email is verified and eligibility passed (§5.1). */
    fun reactivate(now: Instant): AccountChange {
        if (state != AccountState.SUSPENDED) throw AccountError.NotSuspended()
        val missing = buildList {
            if (!emailVerified) add("verified email")
            if (assessment?.passed != true) add("passed credit-eligibility assessment")
        }
        if (missing.isNotEmpty()) throw AccountError.ReactivationPreconditionsNotMet(missing)
        val next = copy(state = AccountState.ACTIVE, suspendedAt = null, suspensionReason = null).touched(now)
        return AccountChange(next, listOf(AccountReactivatedV1(userId.value, now.toString())))
    }

    /** Attaches a new provider subject to this account (identity linking safety net, USR-001). */
    fun linkSubject(newSubject: IdpSubject, now: Instant): Account = copy(subject = newSubject).touched(now)

    private fun touched(now: Instant): Account = copy(updatedAt = now, version = version + 1)

    companion object {
        const val DISPLAY_NAME_MAX = 60
        private val PHONE = Regex("^\\+?[0-9 ().-]{6,20}$")

        /** First authenticated request: entry state follows the `email_verified` claim (USR-013). */
        fun bootstrap(claims: IdentityClaims, now: Instant, id: UUID = UUID.randomUUID(), userId: UserId = UserId.generate()): AccountChange {
            val state = if (claims.emailVerified) AccountState.PENDING_CREDIT_ELIGIBILITY else AccountState.PENDING_VERIFICATION
            val account = Account(
                id = id,
                userId = userId,
                subject = claims.subject,
                email = claims.email,
                emailVerified = claims.emailVerified,
                displayName = claims.displayName,
                state = state,
                createdAt = now,
                updatedAt = now,
            )
            return AccountChange(account, listOf(AccountCreatedV1(userId.value, claims.email.value, claims.emailVerified, state.name, now.toString())))
        }

        fun validateProfile(patch: ProfilePatch): List<FieldError> = buildList {
            patch.displayName?.let {
                if (it.isBlank()) add(FieldError("displayName", "REQUIRED", "display name cannot be blank"))
                else if (it.length > DISPLAY_NAME_MAX) add(FieldError("displayName", "TOO_LONG", "display name is limited to $DISPLAY_NAME_MAX characters"))
            }
            patch.phone?.let { if (!PHONE.matches(it)) add(FieldError("phone", "INVALID", "phone must be digits with an optional + prefix")) }
            patch.shippingAddress?.let { a ->
                listOf("line1" to a.line1, "city" to a.city, "region" to a.region, "postalCode" to a.postalCode, "country" to a.country)
                    .filter { (_, v) -> v.isBlank() }
                    .forEach { (name, _) -> add(FieldError(name, "REQUIRED", "$name is required")) }
                if (a.country.isNotBlank() && a.country.length != 2) add(FieldError("country", "INVALID", "country must be an ISO 3166-1 alpha-2 code"))
            }
        }
    }
}
