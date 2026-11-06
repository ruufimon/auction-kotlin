package com.gavel.onboarding.api

import com.gavel.kernel.UserId

enum class AccountStatus { PENDING_VERIFICATION, ACTIVE, SUSPENDED }

/** Participation facts other modules need to enforce eligibility. */
data class Participant(
    val id: UserId,
    val status: AccountStatus,
    val emailVerified: Boolean,
    val sellerEligible: Boolean,
    val displayName: String,
) {
    val isActive get() = status == AccountStatus.ACTIVE
    val canBid get() = isActive && emailVerified
    val canSell get() = isActive && emailVerified && sellerEligible
}

/** Information approved for public display next to listings. */
data class PublicProfile(val id: UserId, val displayName: String)

/** Where a buyer wants items shipped; shared with a seller only for a paid order. */
data class ShippingContact(
    val name: String,
    val phone: String?,
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String,
    val postalCode: String,
    val country: String,
)

/**
 * Synchronous queries exposed to other modules. Callers inside a transaction
 * read the current committed state, so suspension takes effect immediately.
 */
interface Participants {
    fun find(id: UserId): Participant?
    fun publicProfiles(ids: Collection<UserId>): Map<UserId, PublicProfile>
    fun shippingContact(id: UserId): ShippingContact?
}

/** Integration events published by user onboarding. */
object OnboardingEvents {
    /** Payload: {"userId": "..."}. */
    const val ACCOUNT_SUSPENDED = "onboarding.account-suspended"
}
