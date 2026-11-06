package com.gavel.app

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private suspend fun TestEnv.listing(id: String, token: String? = null): JsonObject = get("/api/v1/listings/$id", token).json()

    @Test
    fun `only sellers with a complete profile can create listings`() = gavelTest {
        val buyer = registerAndVerify()
        val response = post("/api/v1/me/listings", listingInput(), buyer.token)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("SELLER_PROFILE_INCOMPLETE", response.json().code())
    }

    @Test
    fun `invalid prices and missing fields prevent publication`() = gavelTest {
        val seller = seller()
        val badPrice = post("/api/v1/me/listings", listingInput(startingMinor = 0, shippingMinor = -1), seller.token)
        assertEquals(HttpStatusCode.BadRequest, badPrice.status)
        assertEquals(setOf("startingPrice", "shippingPrice"), badPrice.json().arr("fields").map { it.jsonObjectField("field") }.toSet())

        val draft = post("/api/v1/me/listings", mapOf("title" to "Just a title"), seller.token).json()
        assertEquals("DRAFT", draft.str("status"))
        val publish = post("/api/v1/me/listings/${draft.str("id")}/publish", token = seller.token)
        assertEquals(HttpStatusCode.UnprocessableEntity, publish.status)
        val fields = publish.json().arr("fields").map { it.jsonObjectField("field") }.toSet()
        assertTrue(fields.containsAll(setOf("description", "category", "condition", "startingPrice", "images", "startAt", "endAt")), fields.toString())
    }

    @Test
    fun `schedules must end after they start and within limits`() = gavelTest {
        val seller = seller()
        val now = clock.instant()
        val reversed = post("/api/v1/me/listings", listingInput(startAt = now + Duration.ofHours(3), endAt = now + Duration.ofHours(2)), seller.token).json()
        val publish = post("/api/v1/me/listings/${reversed.str("id")}/publish", token = seller.token).json()
        assertEquals("LISTING_INCOMPLETE", publish.code())
        assertTrue(publish.arr("fields").any { it.jsonObjectField("code") == "BEFORE_START" })

        val tooLong = post("/api/v1/me/listings", listingInput(endAt = now + Duration.ofDays(31)), seller.token).json()
        assertTrue(post("/api/v1/me/listings/${tooLong.str("id")}/publish", token = seller.token).json().arr("fields").any { it.jsonObjectField("code") == "TOO_LONG" })
    }

    @Test
    fun `a valid listing goes DRAFT to SCHEDULED to ACTIVE and is publicly visible`() = gavelTest {
        val seller = seller()
        val now = clock.instant()
        val draft = post("/api/v1/me/listings", listingInput(startAt = now + Duration.ofHours(1), endAt = now + Duration.ofHours(3)), seller.token).json()
        val id = draft.str("id")
        assertEquals(HttpStatusCode.NotFound, get("/api/v1/listings/$id").status, "drafts are private")
        assertEquals("DRAFT", listing(id, seller.token).str("status"), "sellers can preview their drafts")

        val scheduled = post("/api/v1/me/listings/$id/publish", token = seller.token)
        assertEquals("SCHEDULED", scheduled.json().str("status"))
        assertEquals("\"2\"", scheduled.headers[HttpHeaders.ETag])

        runJobs()
        assertEquals("SCHEDULED", listing(id).str("status"), "not yet due")
        clock.advance(Duration.ofHours(1))
        runJobs()
        val active = listing(id)
        assertEquals("ACTIVE", active.str("status"))
        assertEquals(10_000, active.obj("currentPrice").long("amountMinor"))
        assertEquals(10_000, active.obj("minimumNextBid").long("amountMinor"))
        assertEquals("Cameras", active.obj("category").str("name"))
        assertTrue(active.obj("seller").str("displayName").startsWith("Seller"))

        runJobs()
        assertEquals(active.long("version"), listing(id).long("version"), "re-running jobs changes nothing")
    }

    @Test
    fun `publishing a listing whose start time has passed activates it immediately`() = gavelTest {
        val id = publishedListing(seller())
        assertEquals("ACTIVE", listing(id).str("status"))
    }

    @Test
    fun `search filters, sorts and paginates stably`() = gavelTest {
        val seller = seller()
        val now = clock.instant()
        val ids = (1..5).map { i ->
            publishedListing(seller, listingInput(title = "Camera number $i", endAt = now + Duration.ofHours(1L + i), startingMinor = 1_000L * i))
        }
        publishedListing(seller, listingInput(title = "Omega Seamaster watch", endAt = now + Duration.ofHours(10)).plus("category" to "watches"))

        val firstPage = get("/api/v1/listings?limit=2").json()
        assertEquals(ids.take(2), firstPage.arr("items").map { it.jsonObjectField("id") })
        val second = get("/api/v1/listings?limit=2&cursor=${firstPage.str("nextCursor")}").json()
        assertEquals(ids.slice(2..3), second.arr("items").map { it.jsonObjectField("id") })

        assertEquals(1, get("/api/v1/listings?category=watches").json().arr("items").size)
        assertEquals(1, get("/api/v1/listings?q=SEAMASTER").json().arr("items").size)
        val priced = get("/api/v1/listings?minPrice=2000&maxPrice=3000&sort=price_desc").json().arr("items").map { it.jsonObjectField("id") }
        assertEquals(listOf(ids[2], ids[1]), priced)
        assertEquals(0, get("/api/v1/listings?status=SOLD").json().arr("items").size)
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/listings?status=DRAFT").status)
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/listings?sort=random").status)
    }

    @Test
    fun `categories are listed`() = gavelTest {
        val categories = get("/api/v1/categories").bodyAsText()
        assertTrue("\"cameras\"" in categories && "\"watches\"" in categories)
    }

    @Test
    fun `active listings lock economic fields but allow description edits`() = gavelTest {
        val seller = seller()
        val id = publishedListing(seller)
        val priceChange = patch("/api/v1/me/listings/$id", mapOf("startingPrice" to money(1)), seller.token)
        assertEquals(HttpStatusCode.Conflict, priceChange.status)
        assertEquals("LISTING_LOCKED", priceChange.json().code())

        val ok = patch("/api/v1/me/listings/$id", mapOf("description" to "Updated description with more than thirty characters."), seller.token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())

        val stale = patch("/api/v1/me/listings/$id", mapOf("description" to "Another description with more than thirty characters."), seller.token) {
            header(HttpHeaders.IfMatch, "\"1\"")
        }
        assertEquals("VERSION_CONFLICT", stale.json().code())
    }

    @Test
    fun `other sellers cannot see or change someone else's listing`() = gavelTest {
        val id = publishedListing(seller())
        val other = seller()
        assertEquals(HttpStatusCode.NotFound, get("/api/v1/me/listings/$id", other.token).status)
        assertEquals(HttpStatusCode.NotFound, post("/api/v1/me/listings/$id/cancel", token = other.token).status)
    }

    @Test
    fun `listings without bids can be cancelled, with bids they cannot`() = gavelTest {
        val seller = seller()
        val buyer = registerAndVerify()
        val quiet = publishedListing(seller)
        assertEquals("CANCELLED", post("/api/v1/me/listings/$quiet/cancel", token = seller.token).json().str("status"))
        assertEquals("AUCTION_NOT_ACTIVE", bid(quiet, buyer, 10_000).json().code())

        val busy = publishedListing(seller)
        assertEquals(HttpStatusCode.Created, bid(busy, buyer, 10_000).status)
        val cancel = post("/api/v1/me/listings/$busy/cancel", token = seller.token)
        assertEquals(HttpStatusCode.Conflict, cancel.status)
        assertEquals("LISTING_HAS_BIDS", cancel.json().code())
    }

    @Test
    fun `closing produces SOLD with bids and UNSOLD without, exactly once`() = gavelTest {
        val seller = seller()
        val buyer = registerAndVerify()
        val sold = publishedListing(seller)
        val unsold = publishedListing(seller)
        bid(sold, buyer, 12_000)

        clock.advance(Duration.ofHours(2))
        runJobs()
        runJobs()

        val soldListing = listing(sold)
        assertEquals("SOLD", soldListing.str("status"))
        assertEquals(12_000, soldListing.obj("currentPrice").long("amountMinor"))
        assertNull(soldListing["minimumNextBid"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
        assertEquals("UNSOLD", listing(unsold).str("status"))
        assertEquals(1, get("/api/v1/listings?status=SOLD").json().arr("items").size)

        val closures = TestDatabase.dataSource.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM platform.audit_log WHERE action = 'auction.closed'").use { it.next(); it.getInt(1) }
        }
        assertEquals(2, closures)
    }

    @Test
    fun `the search price projection follows accepted bids`() = gavelTest {
        val seller = seller()
        val id = publishedListing(seller)
        bid(id, registerAndVerify(), 50_000)
        runJobs() // delivers bidding.bid-accepted to the catalog projection
        val found = get("/api/v1/listings?minPrice=40000").json().arr("items").single().jsonObject
        assertEquals(id, found.str("id"))
        assertEquals(1, found.getValue("bidCount").jsonPrimitive.content.toInt())
    }
}
