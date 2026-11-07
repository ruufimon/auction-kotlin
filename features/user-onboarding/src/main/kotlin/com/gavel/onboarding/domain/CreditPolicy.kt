package com.gavel.onboarding.domain

import java.time.Duration

/** Raw assessment inputs (USR-011). Money is integer minor units. Never logged, never returned. */
data class AssessmentInputs(val age: Int, val annualIncomeMinor: Long, val netWealthMinor: Long) {
    /** Redacted on purpose: these values must not leak through toString() into logs (USR-012). */
    override fun toString(): String = "AssessmentInputs(redacted)"

    companion object {
        const val MINIMUM_AGE = 18

        fun validate(age: Int, annualIncomeMinor: Long, netWealthMinor: Long): List<FieldError> = buildList {
            if (age < MINIMUM_AGE) add(FieldError("age", "BELOW_MINIMUM", "age must be at least $MINIMUM_AGE"))
            if (age > 130) add(FieldError("age", "OUT_OF_RANGE", "age is not plausible"))
            if (annualIncomeMinor < 0) add(FieldError("annualIncome", "NEGATIVE", "annual income cannot be negative"))
            if (netWealthMinor < 0) add(FieldError("netWealth", "NEGATIVE", "net wealth cannot be negative"))
        }
    }
}

enum class EligibilityOutcome { ELIGIBLE, NOT_ELIGIBLE }

/** Stable reasons returned with an outcome (USR-012). */
object EligibilityReasons {
    const val MEETS_POLICY = "MEETS_POLICY"
    const val BELOW_MINIMUM_SCORE = "BELOW_MINIMUM_SCORE"
}

data class Evaluation(val score: Int, val outcome: EligibilityOutcome, val reason: String)

/**
 * Versioned, deployment-configured scoring policy (USR-012). The score is a sum of
 * three capped factors so that no single input dominates:
 *   years of age above the minimum, income per [incomeMinorPerPoint], wealth per [wealthMinorPerPoint].
 * The numeric score is internal and never returned by the API.
 */
data class CreditPolicy(
    val version: String,
    val minimumScore: Int,
    val incomeMinorPerPoint: Long,
    val wealthMinorPerPoint: Long,
    val maxPointsPerFactor: Int,
    /** How long after a failed assessment a new one is accepted. */
    val retryAfter: Duration,
) {
    init {
        require(version.isNotBlank()) { "policy version required" }
        require(incomeMinorPerPoint > 0 && wealthMinorPerPoint > 0) { "per-point divisors must be positive" }
        require(maxPointsPerFactor > 0) { "cap must be positive" }
    }

    fun evaluate(inputs: AssessmentInputs): Evaluation {
        val agePoints = (inputs.age - AssessmentInputs.MINIMUM_AGE).coerceIn(0, maxPointsPerFactor)
        val incomePoints = (inputs.annualIncomeMinor / incomeMinorPerPoint).coerceIn(0, maxPointsPerFactor.toLong()).toInt()
        val wealthPoints = (inputs.netWealthMinor / wealthMinorPerPoint).coerceIn(0, maxPointsPerFactor.toLong()).toInt()
        val score = agePoints + incomePoints + wealthPoints
        return if (score >= minimumScore) Evaluation(score, EligibilityOutcome.ELIGIBLE, EligibilityReasons.MEETS_POLICY)
        else Evaluation(score, EligibilityOutcome.NOT_ELIGIBLE, EligibilityReasons.BELOW_MINIMUM_SCORE)
    }
}
