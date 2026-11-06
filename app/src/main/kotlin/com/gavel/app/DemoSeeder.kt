package com.gavel.app

import com.gavel.catalog.domain.ItemCondition
import com.gavel.catalog.domain.ListingPatch
import com.gavel.catalog.domain.Origin
import com.gavel.kernel.Secrets
import com.gavel.onboarding.application.RegisterCommand
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.SellerProfile
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Seeds the marketplace with the lots the web prototype shows. Enabled with
 * SEED_DEMO_DATA=true; skips sellers that already exist. Demo sellers get random
 * passwords, so nobody can sign in as them.
 */
class DemoSeeder(private val gavel: Gavel) {
    private val log = LoggerFactory.getLogger(DemoSeeder::class.java)

    private data class Lot(
        val seller: String, val city: String, val region: String, val title: String, val category: String,
        val condition: ItemCondition, val startMinor: Long, val shippingMinor: Long, val hours: Long, val image: String, val description: String,
    )

    private val lots = listOf(
        Lot("Northlight Cameras", "Portland", "Oregon", "Leica M6 Classic 0.72", "cameras", ItemCondition.EXCELLENT, 3_000_00, 35_00, 2,
            "https://images.unsplash.com/photo-1590073260031-ca4370c6a35a?auto=format&fit=crop&w=1200&q=85",
            "A beautifully preserved Leica M6 Classic with the sought-after 0.72 viewfinder. The meter is responsive, shutter speeds are accurate, and the body shows only light signs of careful use."),
        Lot("Horology House", "Boston", "Massachusetts", "Omega Seamaster De Ville", "watches", ItemCondition.VERY_GOOD, 1_500_00, 28_00, 4,
            "https://images.unsplash.com/photo-1523170335258-f5ed11844a49?auto=format&fit=crop&w=1000&q=85",
            "A refined vintage Seamaster De Ville with a clean silver dial, applied indices, and its original signed crown. Recently inspected and keeping excellent time."),
        Lot("Form & Timber", "Brooklyn", "New York", "Danish Teak Lounge Chair", "design", ItemCondition.RESTORED, 800_00, 120_00, 6,
            "https://images.unsplash.com/photo-1567538096630-e0c55bd6374c?auto=format&fit=crop&w=1000&q=85",
            "A sculptural mid-century lounge chair in warm teak, professionally restored with fresh webbing and textured neutral upholstery while preserving its original character."),
        Lot("After Hours Records", "Chicago", "Illinois", "Blue Note First Pressing", "records", ItemCondition.NEAR_MINT, 600_00, 18_00, 9,
            "https://images.unsplash.com/photo-1461360228754-6e81c478b882?auto=format&fit=crop&w=1000&q=85",
            "An early Blue Note pressing with a deep-groove label and crisp sleeve. The vinyl has been play-graded and retains remarkable presence and clarity."),
    )

    fun seed() = runBlocking {
        val now = Instant.now()
        val currency = gavel.config.money.currency
        for (lot in lots) {
            val slug = lot.seller.lowercase().replace(Regex("[^a-z]+"), "-").trim('-')
            val origin = Address("1 Demo Street", null, lot.city, lot.region, "00000", "US")
            val seller = gavel.onboarding.service.provisionVerified(
                RegisterCommand("$slug@demo.gavel.invalid", Secrets.randomToken() + "a1", lot.seller, "+1 555 0100", true),
                SellerProfile("Demo seller for the Gavel prototype marketplace.", 2, origin),
            ) ?: continue
            val draft = gavel.catalog.service.createDraft(
                seller.id,
                ListingPatch(
                    title = lot.title, description = lot.description, categorySlug = lot.category, condition = lot.condition,
                    imageUrls = listOf(lot.image), startingPrice = gavel.config.money.money(lot.startMinor),
                    shippingPrice = com.gavel.kernel.Money(lot.shippingMinor, currency), freeShipping = false,
                    origin = Origin(lot.city, lot.region, "US"),
                    startAt = now.minusSeconds(60), endAt = now + Duration.ofHours(lot.hours),
                ),
            )
            gavel.catalog.service.publish(seller.id, draft.listing.id, null)
            log.info("Seeded demo listing '{}'", lot.title)
        }
    }
}
