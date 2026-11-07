package com.gavel.onboarding.api

import kotlinx.serialization.Serializable

/** Integration events published by the user onboarding feature. */
object UserOnboardingEvents {
    /** Payload v1: [UserRegisteredV1]. New user account created. */
    const val USER_REGISTERED = "user-onboarding.user-registered"

    /** Payload v1: [EmailVerifiedV1]. User verified their email address. */
    const val EMAIL_VERIFIED = "user-onboarding.email-verified"

    /** Payload v1: [SellerEnabledV1]. User completed seller profile. */
    const val SELLER_ENABLED = "user-onboarding.seller-enabled"

    /** Payload v1: [AccountSuspendedV1]. User account was suspended. */
    const val ACCOUNT_SUSPENDED = "user-onboarding.account-suspended"

    /** Payload v1: [AccountReactivatedV1]. User account was reactivated. */
    const val ACCOUNT_REACTIVATED = "user-onboarding.account-reactivated"
}

@Serializable
data class UserRegisteredV1(
    val userId: String,
    val email: String,
    val displayName: String,
    val registeredAt: String,
)

@Serializable
data class EmailVerifiedV1(
    val userId: String,
    val email: String,
    val verifiedAt: String,
)

@Serializable
data class SellerEnabledV1(
    val userId: String,
    val businessName: String,
    val enabledAt: String,
)

@Serializable
data class AccountSuspendedV1(
    val userId: String,
    val suspendedAt: String,
    val reason: String,
)

@Serializable
data class AccountReactivatedV1(
    val userId: String,
    val reactivatedAt: String,
)
