package com.gavel.onboarding.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class CreditPolicyTest {
    private val policy = CreditPolicy("test-policy-1", minimumScore = 40, incomeMinorPerPoint = 100_000, wealthMinorPerPoint = 100_000, maxPointsPerFactor = 40, retryAfter = Duration.ofDays(30))

    @Test
    fun `the acceptance fixtures score on the expected sides of the threshold`() {
        val pass = policy.evaluate(AssessmentInputs(34, 6_800_000, 1_500_000))
        assertEquals(EligibilityOutcome.ELIGIBLE, pass.outcome)
        assertEquals(16 + 40 + 15, pass.score)

        val fail = policy.evaluate(AssessmentInputs(19, 900_000, 0))
        assertEquals(EligibilityOutcome.NOT_ELIGIBLE, fail.outcome)
        assertEquals("BELOW_MINIMUM_SCORE", fail.reason)
        assertEquals(1 + 9 + 0, fail.score)
    }

    @Test
    fun `each factor is capped so one input cannot dominate`() {
        val richButYoung = policy.evaluate(AssessmentInputs(18, 0, 100_000_000_000))
        assertEquals(40, richButYoung.score)
    }

    @Test
    fun `the minimum age check lists every invalid field`() {
        assertEquals(listOf("age", "annualIncome"), AssessmentInputs.validate(17, -5, 0).map { it.field })
        assertEquals(emptyList(), AssessmentInputs.validate(18, 0, 0))
    }
}
