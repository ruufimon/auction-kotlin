package com.gavel.onboarding.api

import kotlinx.serialization.Serializable

/**
 * Integration events published by user onboarding (features/user-onboarding/DomainEvents.md).
 * Payloads carry the public user id, never raw assessment inputs or provider secrets.
 */
object UserOnboardingEvents {
    const val AGGREGATE_TYPE = "account"

    /** Payload v1: [AccountCreatedV1]. Application account created on first authenticated request (USR-013). */
    const val ACCOUNT_CREATED = "user-onboarding.account-created"

    /** Payload v1: [EmailVerifiedV1]. Provider reported `email_verified`; account left PENDING_VERIFICATION. */
    const val EMAIL_VERIFIED = "user-onboarding.email-verified"

    /** Payload v1: [CreditEligibilityAssessedV1]. An assessment was scored (either outcome). */
    const val CREDIT_ELIGIBILITY_ASSESSED = "user-onboarding.credit-eligibility-assessed"

    /** Payload v1: [ProfileUpdatedV1]. Display name, contact or address changed. */
    const val PROFILE_UPDATED = "user-onboarding.profile-updated"

    /** Payload v1: [SellerEnabledV1]. Account may now publish listings (consumed by catalog). */
    const val SELLER_ENABLED = "user-onboarding.seller-enabled"

    /** Payload v1: [AccountSuspendedV1]. */
    const val ACCOUNT_SUSPENDED = "user-onboarding.account-suspended"

    /** Payload v1: [AccountReactivatedV1]. */
    const val ACCOUNT_REACTIVATED = "user-onboarding.account-reactivated"
}

/** Marker for every onboarding event payload; [eventType] is the outbox `event_type`. */
sealed interface AccountEvent {
    val eventType: String
    val userId: String
}

@Serializable
data class AccountCreatedV1(
    override val userId: String,
    val email: String,
    val emailVerified: Boolean,
    val accountState: String,
    val createdAt: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.ACCOUNT_CREATED
}

@Serializable
data class EmailVerifiedV1(
    override val userId: String,
    val email: String,
    val verifiedAt: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.EMAIL_VERIFIED
}

@Serializable
data class CreditEligibilityAssessedV1(
    override val userId: String,
    val outcome: String,
    val reason: String,
    val policyVersion: String,
    val assessedAt: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.CREDIT_ELIGIBILITY_ASSESSED
}

@Serializable
data class ProfileUpdatedV1(
    override val userId: String,
    val version: Int,
    val updatedAt: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.PROFILE_UPDATED
}

@Serializable
data class SellerEnabledV1(
    override val userId: String,
    val storeName: String?,
    val enabledAt: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.SELLER_ENABLED
}

@Serializable
data class AccountSuspendedV1(
    override val userId: String,
    val suspendedAt: String,
    val reason: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.ACCOUNT_SUSPENDED
}

@Serializable
data class AccountReactivatedV1(
    override val userId: String,
    val reactivatedAt: String,
) : AccountEvent {
    override val eventType: String get() = UserOnboardingEvents.ACCOUNT_REACTIVATED
}
