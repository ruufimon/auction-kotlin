package com.gavel.kernel

import kotlinx.serialization.Serializable

/** ISO 4217 code. The deployment uses exactly one currency (see [MoneyPolicy]). */
@JvmInline
@Serializable
value class CurrencyCode(val code: String) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "Currency must be an ISO 4217 code" }
    }

    override fun toString() = code
}

/** Monetary amount in integer minor units. Floating-point money is never used. */
@Serializable
data class Money(val amountMinor: Long, val currency: CurrencyCode) : Comparable<Money> {
    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amountMinor.compareTo(other.amountMinor)
    }

    val isNegative get() = amountMinor < 0
    val isPositive get() = amountMinor > 0

    private fun requireSameCurrency(other: Money) =
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }

    companion object {
        fun zero(currency: CurrencyCode) = Money(0, currency)
    }
}

/** Deployment-wide money settings. */
data class MoneyPolicy(val currency: CurrencyCode, val minimumBidIncrement: Money) {
    init {
        require(minimumBidIncrement.currency == currency && minimumBidIncrement.isPositive)
    }

    fun money(amountMinor: Long) = Money(amountMinor, currency)
}
