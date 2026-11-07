package com.gavel.onboarding.service

import com.gavel.kernel.web.ApiException
import com.gavel.kernel.web.ErrorDetail
import com.gavel.onboarding.domain.AccountError
import com.gavel.onboarding.domain.AccountState
import io.ktor.http.HttpStatusCode

/** Domain refusals to HTTP (requirement §6.5): 403 state, 409 conflict, 422 domain rule. */
fun AccountError.toApiException(): ApiException = when (this) {
    is AccountError.NotAllowedInState ->
        if (state == AccountState.ACTIVE) ApiException(HttpStatusCode.Conflict, code, message ?: code)
        else ApiException(HttpStatusCode.Forbidden, code, message ?: code)
    is AccountError.ValidationFailed ->
        ApiException.validation(fields.map { ErrorDetail(field = it.field, code = it.code, message = it.message) })
    is AccountError.SellerProfileIncomplete ->
        ApiException.unprocessable(code, message ?: code, missingFields.map { ErrorDetail(field = it, code = "REQUIRED") })
    is AccountError.SellerAlreadyEnabled -> ApiException.unprocessable(code, message ?: code)
    is AccountError.CreditEligibilityNotMet ->
        ApiException.unprocessable(code, message ?: code, listOf(ErrorDetail(field = "eligibilityReason", code = reason, value = reason)))
    is AccountError.AssessmentRetryNotYetAllowed ->
        ApiException.conflict(code, message ?: code, listOf(ErrorDetail(field = "earliestRetryAt", code = "RETRY_AFTER", value = earliestRetryAt.toString())))
    is AccountError.ReactivationPreconditionsNotMet ->
        ApiException.unprocessable(code, message ?: code, missing.map { ErrorDetail(code = "PRECONDITION", message = it) })
    is AccountError.AlreadySuspended -> ApiException.conflict(code, message ?: code)
    is AccountError.NotSuspended -> ApiException.conflict(code, message ?: code)
}
