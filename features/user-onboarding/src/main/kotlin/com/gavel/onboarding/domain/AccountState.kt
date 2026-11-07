package com.gavel.onboarding.domain

/** requirement.md §5.1 "Account states". */
enum class AccountState(
    /** Stable code returned when an operation is refused because of this state. */
    val errorCode: String,
) {
    PENDING_VERIFICATION("ACCOUNT_PENDING_VERIFICATION"),
    PENDING_CREDIT_ELIGIBILITY("ACCOUNT_PENDING_CREDIT_ELIGIBILITY"),
    ACTIVE("ACCOUNT_ACTIVE"),
    SUSPENDED("ACCOUNT_SUSPENDED");

    /** Stored lower-case in `users.status`. */
    val dbValue: String get() = name.lowercase()

    companion object {
        fun fromDb(value: String): AccountState = valueOf(value.uppercase())
    }
}
