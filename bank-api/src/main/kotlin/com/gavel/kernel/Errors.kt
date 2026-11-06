package com.gavel.kernel

/** HTTP-independent error categories; transport adapters map them to status codes. */
enum class ErrorKind {
    /** Malformed input (400). */
    MALFORMED,

    /** Missing or invalid authentication (401). */
    UNAUTHENTICATED,

    /** Prohibited operation for this actor (403). */
    FORBIDDEN,

    /** Unavailable resource (404). */
    NOT_FOUND,

    /** State or concurrency conflict (409). */
    CONFLICT,

    /** Well-formed request that violates a domain rule (422). */
    RULE_VIOLATION,
}

data class FieldError(val field: String, val code: String, val message: String)

/** A failure with a stable machine-readable [code] and a human-readable message. */
open class DomainException(
    val kind: ErrorKind,
    val code: String,
    message: String,
    val fields: List<FieldError> = emptyList(),
) : RuntimeException(message)

/** Accumulates field-level validation errors and throws them together. */
class Validation {
    private val errors = mutableListOf<FieldError>()

    fun check(condition: Boolean, field: String, code: String, message: String) {
        if (!condition) errors += FieldError(field, code, message)
    }

    fun add(error: FieldError) {
        errors += error
    }

    val isValid get() = errors.isEmpty()
    val all: List<FieldError> get() = errors.toList()

    fun throwIfInvalid(kind: ErrorKind = ErrorKind.MALFORMED, code: String = "VALIDATION_FAILED", message: String = "The request has invalid fields.") {
        if (errors.isNotEmpty()) throw DomainException(kind, code, message, errors.toList())
    }
}

fun notFound(what: String): Nothing = throw DomainException(ErrorKind.NOT_FOUND, "NOT_FOUND", "$what was not found.")
fun forbidden(code: String, message: String): Nothing = throw DomainException(ErrorKind.FORBIDDEN, code, message)
fun conflict(code: String, message: String): Nothing = throw DomainException(ErrorKind.CONFLICT, code, message)
fun ruleViolation(code: String, message: String): Nothing = throw DomainException(ErrorKind.RULE_VIOLATION, code, message)
