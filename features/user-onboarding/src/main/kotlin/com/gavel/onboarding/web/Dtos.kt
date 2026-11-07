package com.gavel.onboarding.web

import com.gavel.kernel.web.ErrorDetail
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.ProfilePatch
import com.gavel.onboarding.service.AssessmentCommand
import com.gavel.onboarding.service.UpdateProfileCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class AddressDto(val line1: String, val city: String, val region: String, val postalCode: String, val country: String) {
    fun toDomain() = Address(line1.trim(), city.trim(), region.trim(), postalCode.trim(), country.trim().uppercase())
    companion object { fun from(a: Address) = AddressDto(a.line1, a.city, a.region, a.postalCode, a.country) }
}

/**
 * Account as returned by `GET /api/v1/me` and the profile endpoints. Never carries raw
 * assessment inputs or a score (USR-012), nor anything credential-like (USR-002, USR-006).
 */
@Serializable
data class AccountResponse(
    val userId: String,
    val email: String,
    val displayName: String?,
    val accountState: String,
    val emailVerified: Boolean,
    val sellerEnabled: Boolean,
    val sellerEnabledAt: String?,
    val creditEligibilityOutcome: String?,
    val eligibilityReason: String?,
    val assessmentPolicyVersion: String?,
    val assessedAt: String?,
    val phone: String?,
    val shippingAddress: AddressDto?,
    val sellerProfile: Map<String, String>,
    val originAddress: Map<String, String>,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
) {
    companion object {
        fun from(a: Account) = AccountResponse(
            userId = a.userId.value,
            email = a.email.value,
            displayName = a.displayName,
            accountState = a.state.name,
            emailVerified = a.emailVerified,
            sellerEnabled = a.sellerEnabled,
            sellerEnabledAt = a.sellerEnabledAt?.toString(),
            creditEligibilityOutcome = a.assessment?.outcome?.name,
            eligibilityReason = a.assessment?.reason,
            assessmentPolicyVersion = a.assessment?.policyVersion,
            assessedAt = a.assessment?.assessedAt?.toString(),
            phone = a.phone,
            shippingAddress = a.shippingAddress?.let(AddressDto::from),
            sellerProfile = a.sellerProfile,
            originAddress = a.originAddress,
            createdAt = a.createdAt.toString(),
            updatedAt = a.updatedAt.toString(),
            version = a.version,
        )
    }
}

/** Numbers arrive as JSON elements so a decimal or string value yields a 422 naming the field, not a 400. */
@Serializable
data class AssessmentRequest(val age: JsonElement? = null, val annualIncome: JsonElement? = null, val netWealth: JsonElement? = null)

@Serializable
data class UpdateProfileRequest(
    val version: Int? = null,
    /** Read-only here: the email is changed at the identity provider. Any value is rejected. */
    val email: String? = null,
    val displayName: String? = null,
    val phone: String? = null,
    val shippingAddress: AddressDto? = null,
    val sellerProfile: Map<String, String>? = null,
    val originAddress: Map<String, String>? = null,
    val assessment: AssessmentRequest? = null,
) {
    /** Returns the command or the field errors that stop it. */
    fun toCommand(): Pair<UpdateProfileCommand?, List<ErrorDetail>> {
        val errors = mutableListOf<ErrorDetail>()
        if (email != null) errors += ErrorDetail(field = "email", code = "READ_ONLY", message = "Email is managed by the identity provider")
        val assessmentCommand = assessment?.let { a ->
            val age = a.age.integer("age", errors)
            val income = a.annualIncome.integer("annualIncome", errors)
            val wealth = a.netWealth.integer("netWealth", errors)
            if (age != null && income != null && wealth != null) {
                if (age !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { errors += ErrorDetail(field = "age", code = "OUT_OF_RANGE", message = "age is not plausible"); null }
                else AssessmentCommand(age.toInt(), income, wealth)
            } else null
        }
        if (errors.isNotEmpty()) return null to errors
        val patch = ProfilePatch(
            displayName = displayName?.trim(),
            phone = phone?.trim(),
            shippingAddress = shippingAddress?.toDomain(),
            sellerProfile = sellerProfile?.mapValues { it.value.trim() },
            originAddress = originAddress?.mapValues { it.value.trim() },
        )
        return UpdateProfileCommand(version, patch, assessmentCommand) to emptyList()
    }

    private fun JsonElement?.integer(field: String, errors: MutableList<ErrorDetail>): Long? {
        if (this == null) { errors += ErrorDetail(field = field, code = "REQUIRED", message = "$field is required"); return null }
        val value = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        if (value == null) errors += ErrorDetail(field = field, code = "INTEGER_REQUIRED", message = "$field must be a whole number (money in integer minor units)")
        return value
    }
}

@Serializable
data class SuspendRequest(val reason: String)
