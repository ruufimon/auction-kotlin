package com.gavel.app

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiddingTest {
    @Test
    fun `the first bid must meet the starting price and later bids the increment`() = gavelTest {
        val id = publishedListing(seller())
        val buyer = registerAndVerify()
        assertEquals("BID_TOO_LOW", bid(id, buyer, 9_999).json().code())

        val first = bid(id, buyer, 10_000)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val body = first.json()
        assertEquals(1, body.long("sequence"))
        assertEquals(10_000, body.obj("auction").obj("currentPrice").long("amountMinor"))
        assertEquals(10_100, body.obj("auction").obj("minimumNextBid").long("amountMinor"))

        val other = registerAndVerify()
        val tooLow = bid(id, other, 10_099)
        assertEquals(HttpStatusCode.UnprocessableEntity, tooLow.status)
        assertEquals("BID_TOO_LOW", tooLow.json().code())
        assertEquals(2, bid(id, other, 10_100).json().long("sequence"))
    }

    @Test
    fun `sellers, unverified, suspended, early and late bids are rejected with stable reasons`() = gavelTest {
        val seller = seller()
        val now = clock.instant()
        val id = publishedListing(seller)
        assertEquals("SELLER_CANNOT_BID", bid(id, seller, 10_000).json().code())

        val suspended = registerAndVerify()
        post("/admin/v1/accounts/${suspended.id}/suspend", mapOf("operator" to "ops")) { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }
        assertEquals(HttpStatusCode.Unauthorized, bid(id, suspended, 10_000).status)

        val early = publishedListing(seller, listingInput(startAt = now + Duration.ofHours(1), endAt = now + Duration.ofHours(3)))
        assertEquals("AUCTION_NOT_ACTIVE", bid(early, registerAndVerify(), 10_000).json().code())

        clock.advance(Duration.ofHours(3))
        val buyer = registerAndVerify()
        assertEquals("AUCTION_ENDED", bid(id, buyer, 10_000).json().code(), "late, even before the closing job runs")
        assertEquals("AUCTION_NOT_ACTIVE", bid("00000000-0000-0000-0000-000000000000", buyer, 10_000).json().code())
    }

    @Test
    fun `a bid received exactly at the deadline fails`() = gavelTest {
        val now = clock.instant()
        val id = publishedListing(seller(), listingInput(endAt = now + Duration.ofHours(2)))
        clock.advance(Duration.ofHours(1).plusMinutes(59))
        val buyer = registerAndVerify()
        clock.now = now.plus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        assertEquals("AUCTION_ENDED", bid(id, buyer, 10_000).json().code())
        clock.now = clock.now.minusMillis(1)
        assertEquals(HttpStatusCode.Created, bid(id, buyer, 10_000).status)
    }

    @Test
    fun `reusing an idempotency key returns the original result`() = gavelTest {
        val id = publishedListing(seller())
        val buyer = registerAndVerify()
        val first = bid(id, buyer, 10_000, key = "retry-1").json()
        val replay = bid(id, buyer, 10_000, key = "retry-1")
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replayed"])
        assertEquals(first.str("bidId"), replay.json().str("bidId"))
        assertEquals(1, get("/api/v1/listings/$id").json().getValue("bidCount").jsonPrimitive.content.toInt())

        val conflicting = bid(id, buyer, 20_000, key = "retry-1")
        assertEquals(HttpStatusCode.Conflict, conflicting.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflicting.json().code())
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", post("/api/v1/listings/$id/bids", mapOf("amount" to money(30_000)), buyer.token).json().code())
    }

    @Test
    fun `concurrent bids are serialized into one deterministic order`() = gavelTest {
        val id = publishedListing(seller())
        val bidders = (1..8).map { registerAndVerify() }
        // Everyone bids at once; amounts are distinct so the winner is well defined.
        val responses = coroutineScope {
            bidders.mapIndexed { i, user -> async { bid(id, user, 10_000L + i * 1_000) } }.awaitAll()
        }
        val accepted = responses.filter { it.status == HttpStatusCode.Created }.map { it.json() }
        val rejected = responses.filter { it.status != HttpStatusCode.Created }.map { it.json().code() }
        assertTrue(rejected.all { it == "BID_TOO_LOW" }, rejected.toString())
        assertTrue(accepted.isNotEmpty())

        val sequences = accepted.map { it.long("sequence") }.sorted()
        assertEquals((1L..accepted.size).toList(), sequences, "sequence numbers are gap-free and unique")
        val bySequence = accepted.sortedBy { it.long("sequence") }.map { it.obj("amount").long("amountMinor") }
        assertEquals(bySequence.sorted(), bySequence, "each accepted bid beats the one before it")

        val listing = get("/api/v1/listings/$id").json()
        assertEquals(bySequence.last(), listing.obj("currentPrice").long("amountMinor"))
        assertEquals(accepted.size, listing.getValue("bidCount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `public history masks bidders and my bids show the full record`() = gavelTest {
        val id = publishedListing(seller())
        val alice = registerAndVerify("Alice")
        val bob = registerAndVerify("Bob")
        bid(id, alice, 10_000)
        clock.advance(Duration.ofSeconds(1))
        bid(id, bob, 10_500)
        clock.advance(Duration.ofSeconds(1))
        bid(id, alice, 11_000)

        val history = get("/api/v1/listings/$id/bids?limit=2").json()
        val page = history.arr("items").map { it.jsonObject }
        assertEquals(listOf(3L, 2L), page.map { it.long("sequence") })
        assertEquals(listOf("Bidder 1", "Bidder 2"), page.map { it.str("bidder") })
        assertTrue("Alice" !in history.toString() && alice.id !in history.toString())
        val rest = get("/api/v1/listings/$id/bids?cursor=${history.str("nextCursor")}").json()
        assertEquals(listOf(1L), rest.arr("items").map { it.jsonObject.long("sequence") })

        val mine = get("/api/v1/me/bids", alice.token).json().arr("items").map { it.jsonObject }
        assertEquals(2, mine.size)
        assertEquals("true", mine.first().getValue("isHighest").jsonPrimitive.content)
        assertEquals("false", mine.last().getValue("isHighest").jsonPrimitive.content)
    }

    @Test
    fun `the winner is the final highest bidder`() = gavelTest {
        val id = publishedListing(seller())
        val alice = registerAndVerify()
        val bob = registerAndVerify()
        bid(id, alice, 10_000)
        bid(id, bob, 15_000)
        clock.advance(Duration.ofHours(3))
        runJobs()
        val orders = get("/api/v1/me/orders?role=buyer", reauth(bob).token).json().arr("items")
        assertEquals(1, orders.size)
        assertEquals(0, get("/api/v1/me/orders?role=buyer", reauth(alice).token).json().arr("items").size)
    }
}
