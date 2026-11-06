package com.gavel.onboarding.domain

import com.gavel.kernel.FieldError
import com.gavel.kernel.UserId
import com.gavel.kernel.Validation
import com.gavel.onboarding.api.AccountStatus
import java.time.Instant

data class Address(
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String,
    val postalCode: String,
    val country: String,
) {
    companion object {
        fun validate(address: Address, prefix: String, v: Validation) {
            v.check(address.line1.trim().length in 3..200, "$prefix.line1", "INVALID", "Enter a street address.")
            v.check((address.line2?.length ?: 0) <= 200, "$prefix.line2", "TOO_LONG", "Keep the second line under 200 characters.")
            v.check(address.city.trim().length in 2..100, "$prefix.city", "INVALID", "Enter a city.")
            v.check(address.region.trim().length in 2..100, "$prefix.region", "INVALID", "Enter a state or region.")
            v.check(address.postalCode.trim().length in 3..20, "$prefix.postalCode", "INVALID", "Enter a postal code.")
            v.check(address.country.matches(Regex("[A-Z]{2}")), "$prefix.country", "INVALID", "Use an ISO 3166-1 alpha-2 country code, such as US.")
        }
    }
}

data class Preferences(
    val favoriteCategories: List<String> = emptyList(),
    val outbidAlerts: Boolean = true,
    val endingSoonAlerts: Boolean = true,
)

data class SellerProfile(val bio: String, val handlingTimeDays: Int, val originAddress: Address)

data class Account(
    val id: UserId,
    val email: String,
    val passwordHash: String,
    val status: AccountStatus,
    val emailVerifiedAt: Instant?,
    val displayName: String,
    val phone: String?,
    val shippingAddress: Address?,
    val preferences: Preferences,
    val sellerProfile: SellerProfile?,
    val termsAcceptedAt: Instant,
    val createdAt: Instant,
    val version: Long,
) {
    val emailVerified get() = emailVerifiedAt != null

    /** USR-008: selling requires every configured seller field. */
    val missingSellerFields: List<String>
        get() = buildList {
            if (displayName.isBlank()) add("displayName")
            if (phone.isNullOrBlank()) add("phone")
            if (sellerProfile == null) addAll(listOf("sellerProfile.bio", "sellerProfile.handlingTimeDays", "sellerProfile.originAddress"))
        }

    val sellerEligible get() = missingSellerFields.isEmpty()

    fun verifyEmail(now: Instant): Account {
        if (emailVerified) return this
        val next = if (status == AccountStatus.PENDING_VERIFICATION) AccountStatus.ACTIVE else status
        return copy(emailVerifiedAt = now, status = next)
    }

    fun suspend(): Account = copy(status = AccountStatus.SUSPENDED)

    /** Reactivation is allowed only once the email has been verified. */
    fun reactivate(): Account? = if (emailVerified) copy(status = AccountStatus.ACTIVE) else null
}

object AccountRules {
    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val phonePattern = Regex("^\\+?[0-9][0-9\\s()-]{6,24}$")

    fun normalizeEmail(raw: String): String = raw.trim().lowercase()

    fun validateEmail(email: String, v: Validation) =
        v.check(email.length <= 254 && emailPattern.matches(email), "email", "INVALID", "Enter a valid email address.")

    fun validateDisplayName(name: String, v: Validation) =
        v.check(name.trim().length in 2..60, "displayName", "INVALID", "Display names must be 2–60 characters.")

    fun validatePhone(phone: String, v: Validation) =
        v.check(phonePattern.matches(phone.trim()), "phone", "INVALID", "Enter a valid phone number.")

    fun validateSellerProfile(profile: SellerProfile, v: Validation) {
        v.check(profile.bio.trim().length in 20..280, "bio", "INVALID", "Write a bio of 20–280 characters.")
        v.check(profile.handlingTimeDays in 1..10, "handlingTimeDays", "OUT_OF_RANGE", "Handling time must be 1–10 business days.")
        Address.validate(profile.originAddress, "originAddress", v)
    }

    fun validateCategories(categories: List<String>, v: Validation) =
        v.check(categories.size <= 20 && categories.all { it.length in 1..60 }, "preferences.favoriteCategories", "INVALID", "Choose up to 20 categories.")
}

/** USR-002: configurable minimum policy. */
data class PasswordPolicy(val minLength: Int = 8, val maxLength: Int = 128) {
    fun violations(password: String): List<FieldError> = buildList {
        if (password.length < minLength) add(FieldError("password", "TOO_SHORT", "Use at least $minLength characters."))
        if (password.length > maxLength) add(FieldError("password", "TOO_LONG", "Use at most $maxLength characters."))
        if (!password.any(Char::isLetter) || !password.any(Char::isDigit)) {
            add(FieldError("password", "TOO_WEAK", "Use at least one letter and one number."))
        }
    }
}
