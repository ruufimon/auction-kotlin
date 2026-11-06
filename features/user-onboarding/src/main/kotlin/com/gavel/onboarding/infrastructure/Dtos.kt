package com.gavel.onboarding.infrastructure

import com.gavel.onboarding.application.Session
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.Preferences
import com.gavel.onboarding.domain.SellerProfile
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val phone: String? = null,
    val acceptedTerms: Boolean = false,
)

@Serializable
data class SignInRequest(val email: String, val password: String)

@Serializable
data class VerifyEmailRequest(val token: String)

@Serializable
data class ResendVerificationRequest(val email: String)

@Serializable
data class AddressDto(
    val line1: String,
    val line2: String? = null,
    val city: String,
    val region: String,
    val postalCode: String,
    val country: String,
) {
    fun toDomain() = Address(line1, line2, city, region, postalCode, country.uppercase())
}

@Serializable
data class PreferencesDto(
    val favoriteCategories: List<String> = emptyList(),
    val outbidAlerts: Boolean = true,
    val endingSoonAlerts: Boolean = true,
) {
    fun toDomain() = Preferences(favoriteCategories.distinct(), outbidAlerts, endingSoonAlerts)
}

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val phone: String? = null,
    val shippingAddress: AddressDto? = null,
    val preferences: PreferencesDto? = null,
)

@Serializable
data class SellerProfileDto(val bio: String, val handlingTimeDays: Int, val originAddress: AddressDto) {
    fun toDomain() = SellerProfile(bio, handlingTimeDays, originAddress.toDomain())
}

@Serializable
data class MeDto(
    val id: String,
    val email: String,
    val status: String,
    val emailVerified: Boolean,
    val displayName: String,
    val phone: String?,
    val shippingAddress: AddressDto?,
    val preferences: PreferencesDto,
    val sellerProfile: SellerProfileDto?,
    val sellerEligible: Boolean,
    val missingSellerFields: List<String>,
    val createdAt: String,
    val version: Long,
)

@Serializable
data class SessionDto(val accessToken: String, val tokenType: String = "Bearer", val expiresIn: Long, val user: MeDto)

internal fun Address.toDto() = AddressDto(line1, line2, city, region, postalCode, country)

internal fun Account.toDto() = MeDto(
    id = id.toString(),
    email = email,
    status = status.name,
    emailVerified = emailVerified,
    displayName = displayName,
    phone = phone,
    shippingAddress = shippingAddress?.toDto(),
    preferences = PreferencesDto(preferences.favoriteCategories, preferences.outbidAlerts, preferences.endingSoonAlerts),
    sellerProfile = sellerProfile?.let { SellerProfileDto(it.bio, it.handlingTimeDays, it.originAddress.toDto()) },
    sellerEligible = sellerEligible,
    missingSellerFields = missingSellerFields,
    createdAt = createdAt.toString(),
    version = version,
)

internal fun Session.toDto() = SessionDto(accessToken = accessToken, expiresIn = expiresInSeconds, user = account.toDto())
