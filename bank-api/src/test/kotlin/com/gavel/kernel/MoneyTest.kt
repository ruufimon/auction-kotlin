package com.gavel.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {
    private val usd = CurrencyCode("USD")

    @Test
    fun `adds and compares in minor units`() {
        assertEquals(Money(1_050, usd), Money(1_000, usd) + Money(50, usd))
        assertTrue(Money(1, usd) < Money(2, usd))
    }

    @Test
    fun `refuses to mix currencies or overflow`() {
        assertFailsWith<IllegalArgumentException> { Money(1, usd) + Money(1, CurrencyCode("EUR")) }
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE, usd) + Money(1, usd) }
        assertFailsWith<IllegalArgumentException> { CurrencyCode("usd") }
    }

    @Test
    fun `cursors round-trip and reject garbage`() {
        assertEquals(listOf("a", "b"), Cursor.decode(Cursor.encode("a", "b"), 2))
        assertFailsWith<DomainException> { Cursor.decode("%%%", 2) }
        assertFailsWith<DomainException> { PageRequest.of(0, null) }
    }
}
