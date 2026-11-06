package com.gavel.app

import com.gavel.kernel.EventEnvelope
import com.gavel.kernel.EventHandler
import com.gavel.kernel.Metrics
import com.gavel.onboarding.infrastructure.Argon2PasswordHasher
import com.gavel.onboarding.infrastructure.CapturingEmailSender
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformTest {
    private fun query(sql: String): List<List<String?>> = TestDatabase.dataSource.connection.use { c ->
        c.createStatement().executeQuery(sql).use { rs ->
            buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
        }
    }

    @Test
    fun `correlation ids flow into audit records and outbox events`() = gavelTest {
        val seller = seller()
        val id = publishedListing(seller)
        val buyer = registerAndVerify()
        val response = post("/api/v1/listings/$id/bids", mapOf("amount" to money(10_000)), buyer.token) {
            header("Idempotency-Key", "k1")
            header("X-Correlation-Id", "trace-bid-0001")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(listOf(listOf("trace-bid-0001")), query("SELECT correlation_id FROM platform.audit_log WHERE action = 'bid.accepted'"))
        assertEquals(listOf(listOf("trace-bid-0001")), query("SELECT correlation_id FROM platform.outbox_events WHERE event_type = 'bidding.bid-accepted'"))
    }

    @Test
    fun `failing handlers are retried with backoff and dead-lettered`() = gavelTest {
        publishedListing(seller()).let { bid(it, registerAndVerify(), 10_000) }
        var calls = 0
        val failing = object : EventHandler {
            override val name = "always-fails"
            override val eventType = "bidding.bid-accepted"
            override val payloadVersion = 1
            override fun handle(event: EventEnvelope) {
                calls++
                error("boom")
            }
        }
        val dispatcher = OutboxDispatcher(gavel.tx, listOf(failing), Metrics.None, clock, maxAttempts = 3, baseBackoff = Duration.ofSeconds(10))

        dispatcher.dispatchDue()
        assertEquals(1, calls)
        dispatcher.dispatchDue()
        assertEquals(1, calls, "backing off")
        clock.advance(Duration.ofSeconds(10))
        dispatcher.dispatchDue()
        clock.advance(Duration.ofSeconds(20))
        dispatcher.dispatchDue()
        assertEquals(3, calls)
        assertEquals(listOf(listOf("DEAD", "3")), query("SELECT status, attempts FROM platform.outbox_events"))
        assertEquals(1L, dispatcher.dead.get())
    }

    @Test
    fun `registration through delivery survives a restart with pending work`() = gavelTest {
        val seller = seller()
        val buyer = registerAndVerify()
        val listingId = publishedListing(seller)
        bid(listingId, buyer, 25_000)
        clock.advance(Duration.ofHours(2))

        // Close the auction but "crash" before the outbox is dispatched.
        gavel.catalog.service.closeDue()
        assertEquals(listOf(listOf("PENDING")), query("SELECT status FROM platform.outbox_events WHERE event_type = 'catalog.auction-sold'"))

        // A fresh instance against the same database picks the work up.
        val restarted = Gavel(gavel.config, TestDatabase.dataSource, clock, CapturingEmailSender(), Argon2PasswordHasher(memoryKiB = 1024, iterations = 1))
        restarted.runJobsOnce()
        assertEquals(listOf(listOf("PAYMENT_PENDING", "26500")), query("SELECT status, item_price_minor + shipping_price_minor FROM settlement.orders"))

        val b = reauth(buyer)
        val s = reauth(seller)
        val orderId = get("/api/v1/me/orders", b.token).json().arr("items").single().jsonObjectField("id")
        val reference = post("/api/v1/orders/$orderId/checkout", token = b.token).json().str("checkoutUrl").substringAfterLast('/')
        assertEquals(HttpStatusCode.OK, webhook("payment.succeeded", reference, 26_500).status)
        post("/api/v1/orders/$orderId/shipment", mapOf("carrier" to "USPS", "trackingReference" to "9400100000000000000000"), s.token)
        assertEquals("DELIVERED", post("/api/v1/orders/$orderId/delivery", token = b.token).json().str("status"))
    }
}
