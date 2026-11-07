package com.gavel.kernel.web

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** One field-level problem inside an [ApiError] (requirement §6.5). */
@Serializable
data class ErrorDetail(
    val field: String? = null,
    val code: String,
    val message: String? = null,
    val value: String? = null,
)

/** The only error body the API produces: correlation id, stable code, message, details. */
@Serializable
data class ApiError(
    val correlationId: String,
    val code: String,
    val message: String,
    val details: List<ErrorDetail> = emptyList(),
)

/** Thrown anywhere in a request; [installWebKernel] turns it into an [ApiError] response. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: List<ErrorDetail> = emptyList(),
) : RuntimeException(message) {
    companion object {
        fun unauthorized(code: String, message: String) = ApiException(HttpStatusCode.Unauthorized, code, message)
        fun forbidden(code: String, message: String) = ApiException(HttpStatusCode.Forbidden, code, message)
        fun conflict(code: String, message: String, details: List<ErrorDetail> = emptyList()) =
            ApiException(HttpStatusCode.Conflict, code, message, details)
        fun unprocessable(code: String, message: String, details: List<ErrorDetail> = emptyList()) =
            ApiException(HttpStatusCode.UnprocessableEntity, code, message, details)
        fun validation(details: List<ErrorDetail>) =
            ApiException(HttpStatusCode.UnprocessableEntity, "VALIDATION_FAILED", "The request contains invalid fields", details)
    }
}
