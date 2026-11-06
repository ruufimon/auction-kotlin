package com.gavel.catalog.domain

import com.gavel.kernel.FieldError
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.UserId
import com.gavel.kernel.Validation
import java.net.URI
import java.time.Duration
import java.time.Instant

enum class ListingStatus {
    DRAFT, SCHEDULED, ACTIVE, SOLD, UNSOLD, CANCELLED;

    val isPublic get() = this in PUBLIC

    companion object {
        /** CAT-007: statuses visitors may browse. */
        val PUBLIC = setOf(SCHEDULED, ACTIVE, SOLD, UNSOLD)
    }
}

enum class ItemCondition { NEW, NEAR_MINT, EXCELLENT, VERY_GOOD, GOOD, FAIR, RESTORED, FOR_PARTS }

data class Origin(val city: String, val region: String, val country: String)

/** Seller-editable content. Drafts may be incomplete; publication requires every field. */
data class ListingContent(
    val title: String? = null,
    val description: String? = null,
    val categorySlug: String? = null,
    val condition: ItemCondition? = null,
    val imageUrls: List<String> = emptyList(),
    val startingPrice: Money? = null,
    val shippingPrice: Money? = null,
    val freeShipping: Boolean = false,
    val origin: Origin? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
) {
    /** Free shipping always means a zero shipping price. */
    val effectiveShippingPrice: Money? get() = if (freeShipping) startingPrice?.let { Money.zero(it.currency) } else shippingPrice
}

data class Listing(
    val id: ListingId,
    val sellerId: UserId,
    val status: ListingStatus,
    val content: ListingContent,
    val currentPrice: Money?,
    val bidCount: Int,
    val publishedAt: Instant?,
    val closedAt: Instant?,
    val cancelledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class CatalogSettings(
    val minDuration: Duration = Duration.ofHours(1),
    val maxDuration: Duration = Duration.ofDays(30),
    val maxStartLead: Duration = Duration.ofDays(90),
    val maxImages: Int = 12,
)

/** A partial update. Null leaves a field unchanged. */
data class ListingPatch(
    val title: String? = null,
    val description: String? = null,
    val categorySlug: String? = null,
    val condition: ItemCondition? = null,
    val imageUrls: List<String>? = null,
    val startingPrice: Money? = null,
    val shippingPrice: Money? = null,
    val freeShipping: Boolean? = null,
    val origin: Origin? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
) {
    /** CAT-005: fields that change auction economics or timing. */
    val touchesEconomics get() = listOf(title, categorySlug, startingPrice, shippingPrice, freeShipping, startAt, endAt, origin).any { it != null }

    fun applyTo(c: ListingContent) = c.copy(
        title = title?.trim() ?: c.title,
        description = description?.trim() ?: c.description,
        categorySlug = categorySlug ?: c.categorySlug,
        condition = condition ?: c.condition,
        imageUrls = imageUrls ?: c.imageUrls,
        startingPrice = startingPrice ?: c.startingPrice,
        shippingPrice = shippingPrice ?: c.shippingPrice,
        freeShipping = freeShipping ?: c.freeShipping,
        origin = origin?.let { Origin(it.city.trim(), it.region.trim(), it.country.uppercase()) } ?: c.origin,
        startAt = startAt ?: c.startAt,
        endAt = endAt ?: c.endAt,
    )
}

object ListingRules {
    /** Shape checks that apply to any value a seller sends, even in drafts. */
    fun validateShape(c: ListingContent, settings: CatalogSettings, v: Validation) {
        c.title?.let { v.check(it.trim().length in 5..80, "title", "INVALID", "Titles must be 5–80 characters.") }
        c.description?.let { v.check(it.trim().length in 30..5000, "description", "INVALID", "Descriptions must be 30–5000 characters.") }
        v.check(c.imageUrls.size <= settings.maxImages, "images", "TOO_MANY", "Add at most ${settings.maxImages} images.")
        c.imageUrls.forEachIndexed { i, url -> v.check(isHttpsUrl(url), "images[$i]", "INVALID", "Image references must be https URLs.") }
        c.startingPrice?.let { v.check(it.isPositive, "startingPrice", "NOT_POSITIVE", "The starting price must be greater than zero.") }
        c.shippingPrice?.let { v.check(!it.isNegative, "shippingPrice", "NEGATIVE", "The shipping price cannot be negative.") }
        c.origin?.let {
            v.check(it.city.isNotBlank() && it.city.length <= 100, "origin.city", "INVALID", "Enter the city the item ships from.")
            v.check(it.region.isNotBlank() && it.region.length <= 100, "origin.region", "INVALID", "Enter the state or region the item ships from.")
            v.check(it.country.matches(Regex("[A-Z]{2}")), "origin.country", "INVALID", "Use an ISO 3166-1 alpha-2 country code.")
        }
    }

    /** CAT-002/003/004: everything publication needs. */
    fun publicationErrors(c: ListingContent, now: Instant, settings: CatalogSettings, categoryExists: (String) -> Boolean): List<FieldError> {
        val v = Validation()
        validateShape(c, settings, v)
        fun required(value: Any?, field: String) = v.check(value != null, field, "REQUIRED", "$field is required to publish.")
        required(c.title, "title")
        required(c.description, "description")
        required(c.categorySlug, "category")
        required(c.condition, "condition")
        required(c.startingPrice, "startingPrice")
        if (!c.freeShipping) required(c.shippingPrice, "shippingPrice")
        required(c.origin, "origin")
        required(c.startAt, "startAt")
        required(c.endAt, "endAt")
        v.check(c.imageUrls.isNotEmpty(), "images", "REQUIRED", "Add at least one image.")
        c.categorySlug?.let { v.check(categoryExists(it), "category", "UNKNOWN", "Choose an existing category.") }
        val start = c.startAt
        val end = c.endAt
        if (start != null && end != null) {
            v.check(end.isAfter(start), "endAt", "BEFORE_START", "The end time must be later than the start time.")
            val duration = Duration.between(start, end)
            v.check(duration >= settings.minDuration, "endAt", "TOO_SHORT", "Auctions must run for at least ${settings.minDuration.toHours()} hour(s).")
            v.check(duration <= settings.maxDuration, "endAt", "TOO_LONG", "Auctions can run for at most ${settings.maxDuration.toDays()} days.")
            v.check(!start.isAfter(now + settings.maxStartLead), "startAt", "TOO_FAR", "Auctions must start within ${settings.maxStartLead.toDays()} days.")
            v.check(Duration.between(now, end) >= settings.minDuration, "endAt", "TOO_SOON", "The end time must be at least ${settings.minDuration.toHours()} hour(s) from now.")
        }
        return v.all
    }

    private fun isHttpsUrl(url: String): Boolean =
        url.length <= 2000 && runCatching { URI(url) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrBlank() } == true
}
