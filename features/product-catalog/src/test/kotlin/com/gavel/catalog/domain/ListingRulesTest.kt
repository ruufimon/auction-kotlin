package com.gavel.catalog.domain

import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.Money
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingRulesTest {
    private val usd = CurrencyCode("USD")
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val settings = CatalogSettings()
    private val complete = ListingContent(
        title = "Leica M6 Classic", description = "A beautifully preserved camera with clean optics.",
        categorySlug = "cameras", condition = ItemCondition.EXCELLENT, imageUrls = listOf("https://img.example.com/a.jpg"),
        startingPrice = Money(10_000, usd), shippingPrice = Money(1_500, usd), origin = Origin("Portland", "Oregon", "US"),
        startAt = now, endAt = now + Duration.ofDays(3),
    )

    private fun errors(c: ListingContent) = ListingRules.publicationErrors(c, now, settings) { it == "cameras" }.map { it.field to it.code }

    @Test
    fun `a complete listing is publishable`() = assertEquals(emptyList(), errors(complete))

    @Test
    fun `prices, images and categories are validated`() {
        val e = errors(complete.copy(startingPrice = Money(0, usd), shippingPrice = Money(-1, usd), imageUrls = listOf("http://insecure.example.com/a.jpg"), categorySlug = "boats"))
        assertTrue(("startingPrice" to "NOT_POSITIVE") in e)
        assertTrue(("shippingPrice" to "NEGATIVE") in e)
        assertTrue(("images[0]" to "INVALID") in e)
        assertTrue(("category" to "UNKNOWN") in e)
    }

    @Test
    fun `free shipping needs no shipping price and means zero`() {
        val free = complete.copy(shippingPrice = null, freeShipping = true)
        assertEquals(emptyList(), errors(free))
        assertEquals(Money(0, usd), free.effectiveShippingPrice)
    }

    @Test
    fun `schedules must be ordered and within limits`() {
        assertTrue(("endAt" to "BEFORE_START") in errors(complete.copy(endAt = now.minusSeconds(1))))
        assertTrue(("endAt" to "TOO_SHORT") in errors(complete.copy(endAt = now + Duration.ofMinutes(30))))
        assertTrue(("endAt" to "TOO_LONG") in errors(complete.copy(endAt = now + Duration.ofDays(31))))
        assertTrue(("startAt" to "TOO_FAR") in errors(complete.copy(startAt = now + Duration.ofDays(91), endAt = now + Duration.ofDays(92))))
    }

    @Test
    fun `only economic fields are locked during an auction`() {
        assertTrue(ListingPatch(startingPrice = Money(1, usd)).touchesEconomics)
        assertTrue(!ListingPatch(description = "new", imageUrls = listOf("https://x.example/a.jpg")).touchesEconomics)
    }
}
