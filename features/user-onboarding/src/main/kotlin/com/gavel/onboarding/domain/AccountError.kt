package com.gavel.onboarding.domain

import java.time.Instant

/** A field that failed validation; [code] is stable, [message] is for humans. */
data class FieldError(val field: String, val code: String, val message: String)

/**
 * Every way an account operation can be refused. Codes are the API contract
 * (docs/M2_ONBOARDING_ACCEPTANCE.md); the web layer maps them to HTTP statuses.
 */
sealed class AccountError(val code: String, message: String) : RuntimeException(message) {
    /** The operation is not allowed while the account is in [state] (USR-004, USR-010, USR-012). */
    class NotAllowedInState(val state: AccountState, operation: String) :
        AccountError(state.errorCode, "$operation is not allowed while the account is ${state.name}")

    class ValidationFailed(val fields: List<FieldError>) :
        AccountError("VALIDATION_FAILED", "Invalid fields: ${fields.joinToString { it.field }}")

    class SellerProfileIncomplete(val missingFields: List<String>) :
        AccountError("SELLER_PROFILE_INCOMPLETE", "Missing seller fields: ${missingFields.joinToString()}")

    class SellerAlreadyEnabled : AccountError("SELLER_ALREADY_ENABLED", "Selling is already enabled")

    class CreditEligibilityNotMet(val reason: String) :
        AccountError("CREDIT_ELIGIBILITY_NOT_MET", "The assessment did not meet the configured policy")

    class AssessmentRetryNotYetAllowed(val earliestRetryAt: Instant) :
        AccountError("ASSESSMENT_RETRY_NOT_YET_ALLOWED", "A new assessment is allowed from $earliestRetryAt")

    class ReactivationPreconditionsNotMet(val missing: List<String>) :
        AccountError("REACTIVATION_PRECONDITIONS_NOT_MET", "Reactivation requires: ${missing.joinToString()}")

    class AlreadySuspended : AccountError("ACCOUNT_ALREADY_SUSPENDED", "The account is already suspended")

    class NotSuspended : AccountError("ACCOUNT_NOT_SUSPENDED", "Only suspended accounts can be reactivated")
}
