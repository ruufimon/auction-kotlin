package com.gavel.onboarding.config

import com.gavel.onboarding.domain.CreditPolicy
import com.gavel.onboarding.domain.SellerRequirements
import java.time.Duration

/**
 * Feature configuration from environment variables. Outside production every value
 * has a local-development default; in production the secrets are required.
 */
data class UserOnboardingSettings(
    /** Base64 of 32 random bytes; encrypts assessment inputs at rest (USR-012). */
    val assessmentEncryptionKey: String,
    val creditPolicy: CreditPolicy,
    val sellerRequirements: SellerRequirements,
    /** Shared secret for the operator endpoints under /internal/accounts; null disables them. */
    val internalApiToken: String?,
    val bootstrapRateLimitPerMinute: Int,
) {
    companion object {
        /** Development-only key; the app refuses to start in production without a real one. */
        const val DEV_ENCRYPTION_KEY = "ZGV2LW9ubHktYXNzZXNzbWVudC1rZXktMDAwMDAwMDA="

        fun fromEnv(getenv: (String) -> String?, production: Boolean): UserOnboardingSettings {
            val missing = mutableListOf<String>()
            fun opt(name: String) = getenv(name)?.takeIf { it.isNotBlank() }
            fun int(name: String, default: Int) = opt(name)?.let { it.toIntOrNull() ?: throw IllegalStateException("$name must be an integer, got '$it'") } ?: default
            fun long(name: String, default: Long) = opt(name)?.let { it.toLongOrNull() ?: throw IllegalStateException("$name must be an integer, got '$it'") } ?: default
            fun list(name: String, default: List<String>) = opt(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

            val key = opt("ASSESSMENT_ENCRYPTION_KEY") ?: run { if (production) missing += "ASSESSMENT_ENCRYPTION_KEY"; DEV_ENCRYPTION_KEY }
            val internalToken = opt("INTERNAL_API_TOKEN") ?: run { if (production) missing += "INTERNAL_API_TOKEN"; null }
            check(missing.isEmpty()) { "Missing required environment variables for APP_ENV=production: ${missing.joinToString()}" }

            return UserOnboardingSettings(
                assessmentEncryptionKey = key,
                creditPolicy = CreditPolicy(
                    version = opt("CREDIT_POLICY_VERSION") ?: "policy-2026-11",
                    minimumScore = int("CREDIT_MIN_SCORE", 40),
                    incomeMinorPerPoint = long("CREDIT_INCOME_MINOR_PER_POINT", 100_000),
                    wealthMinorPerPoint = long("CREDIT_WEALTH_MINOR_PER_POINT", 100_000),
                    maxPointsPerFactor = int("CREDIT_MAX_POINTS_PER_FACTOR", 40),
                    retryAfter = Duration.ofDays(int("CREDIT_RETRY_DAYS", 30).toLong()),
                ),
                sellerRequirements = SellerRequirements(
                    sellerProfileFields = list("SELLER_REQUIRED_FIELDS", SellerRequirements.DEFAULT.sellerProfileFields),
                    originAddressFields = list("SELLER_REQUIRED_ORIGIN_FIELDS", SellerRequirements.DEFAULT.originAddressFields),
                ),
                internalApiToken = internalToken,
                bootstrapRateLimitPerMinute = int("BOOTSTRAP_RATE_LIMIT_PER_MINUTE", 60),
            )
        }
    }
}
