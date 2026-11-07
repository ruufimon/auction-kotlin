package com.gavel.onboarding.domain

/**
 * Which seller-profile and origin-address fields must be present before selling is
 * enabled (USR-008). Configuration, not code: the deployment can add e.g. `taxId`.
 */
data class SellerRequirements(
    val sellerProfileFields: List<String>,
    val originAddressFields: List<String>,
) {
    /** Missing field names, origin-address ones prefixed `originAddress.`. */
    fun missingIn(sellerProfile: Map<String, String>, originAddress: Map<String, String>): List<String> =
        sellerProfileFields.filter { sellerProfile[it].isNullOrBlank() } +
            originAddressFields.filter { originAddress[it].isNullOrBlank() }.map { "originAddress.$it" }

    companion object {
        val DEFAULT = SellerRequirements(
            sellerProfileFields = listOf("legalName", "storeName", "contactPhone", "payoutAccount"),
            originAddressFields = listOf("line1", "city", "region", "postalCode", "country"),
        )
    }
}
