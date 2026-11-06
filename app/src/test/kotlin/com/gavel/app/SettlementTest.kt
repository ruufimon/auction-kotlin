package com.gavel.app

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettlementTest {
    private class Sale(val orderId: String, val buyer: TestEnv.User, val seller: TestEnv.User, val other: TestEnv.User)

    /** Runs an auction to a sale and returns the resulting order, with fresh tokens. */
    private suspend fun TestEnv.sale(winningMinor: Long = 20_000): Sale {
        val seller = seller()
        val buyer = registerAndVerify()
        val other = registerAndVerify()
        val id = publishedListing(seller)
        bid(id, other, 10_000)
        bid(id, buyer, winningMinor)
        clock.advance(Duration.ofHours(2))
        runJobs()
        val b = reauth(buyer)
        val orders = get("/api/v1/me/orders", b.token).json().arr("items")
        assertEquals(1, orders.size)
        return Sale(orders.single().jsonObjectField("id"), b, reauth(seller), reauth(other))
    }

    private suspend fun TestEnv.checkout(sale: Sale) = post("/api/v1/orders/${sale.orderId}/checkout", token = sale.buyer.token)

    private suspend fun TestEnv.reference(sale: Sale) = checkout(sale).json().str("checkoutUrl").substringAfterLast('/')

    @Test
    fun `closing a sold auction creates exactly one order for the highest bidder`() = gavelTest {
        val sale = sale(winningMinor = 20_000)
        val order = get("/api/v1/orders/${sale.orderId}", sale.buyer.token).json()
        assertEquals("PAYMENT_PENDING", order.str("status"))
        assertEquals(20_000, order.obj("itemPrice").long("amountMinor"))
        assertEquals(1_500, order.obj("shippingPrice").long("amountMinor"))
        assertEquals(21_500, order.obj("total").long("amountMinor"))
        assertEquals((clock.instant() + Duration.ofHours(24)).toString(), order.str("paymentDeadline"), "24 hours after closing")

        // Redelivering the auction-sold event must not create a second order.
        TestDatabase.dataSource.connection.use { c ->
            c.createStatement().execute("UPDATE platform.outbox_events SET status = 'PENDING' WHERE event_type = 'catalog.auction-sold'")
            c.commit()
        }
        runJobs()
        assertEquals(1, get("/api/v1/me/orders?role=seller", sale.seller.token).json().arr("items").size)
        assertEquals(HttpStatusCode.NotFound, get("/api/v1/orders/${sale.orderId}", sale.other.token).status)
    }

    @Test
    fun `only the winning buyer can check out, and checkout resumes the open attempt`() = gavelTest {
        val sale = sale()
        assertEquals(HttpStatusCode.NotFound, post("/api/v1/orders/${sale.orderId}/checkout", token = sale.other.token).status)
        assertEquals(HttpStatusCode.NotFound, post("/api/v1/orders/${sale.orderId}/checkout", token = sale.seller.token).status)

        val first = checkout(sale)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val resumed = checkout(sale)
        assertEquals(HttpStatusCode.OK, resumed.status)
        assertEquals(first.json().str("checkoutUrl"), resumed.json().str("checkoutUrl"))
        assertEquals("true", resumed.json().str("resumed"))
    }

    @Test
    fun `the seller sees the shipping address only after payment`() = gavelTest {
        val sale = sale()
        assertTrue(get("/api/v1/orders/${sale.orderId}", sale.seller.token).json()["shipTo"] is JsonNull)
        webhook("payment.succeeded", reference(sale), 21_500)
        assertEquals("Portland", get("/api/v1/orders/${sale.orderId}", sale.seller.token).json().obj("shipTo").str("city"))
    }

    @Test
    fun `webhooks require a valid signature`() = gavelTest {
        val sale = sale()
        val ref = reference(sale)
        val forged = webhook("payment.succeeded", ref, 21_500, secret = "not-the-real-secret-1234")
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertEquals("WEBHOOK_SIGNATURE_INVALID", forged.json().code())
        val unsigned = client.post("/api/v1/payments/webhooks/sandbox") { setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
        assertEquals("PAYMENT_PENDING", get("/api/v1/orders/${sale.orderId}", sale.buyer.token).json().str("status"))
    }

    @Test
    fun `a success webhook marks the order paid once and duplicates have no effect`() = gavelTest {
        val sale = sale()
        val ref = reference(sale)
        assertEquals("PAYMENT_PENDING", get("/api/v1/orders/${sale.orderId}", sale.buyer.token).json().str("status"), "checkout alone never pays")

        assertEquals(HttpStatusCode.OK, webhook("payment.succeeded", ref, 21_500, eventId = "evt_1").status)
        val paid = get("/api/v1/orders/${sale.orderId}", sale.buyer.token).json()
        assertEquals("PAID", paid.str("status"))

        assertEquals(HttpStatusCode.OK, webhook("payment.succeeded", ref, 21_500, eventId = "evt_1").status)
        assertEquals(HttpStatusCode.OK, webhook("payment.succeeded", ref, 21_500, eventId = "evt_2").status)
        val after = get("/api/v1/orders/${sale.orderId}", sale.buyer.token).json()
        assertEquals(paid.long("version"), after.long("version"))
        val paidEvents = TestDatabase.dataSource.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM platform.outbox_events WHERE event_type = 'settlement.order-paid'").use { it.next(); it.getInt(1) }
        }
        assertEquals(1, paidEvents)
        assertEquals("ORDER_NOT_PAYABLE", checkout(sale).json().code())
    }

    @Test
    fun `failed attempts allow another checkout before the deadline`() = gavelTest {
        val sale = sale()
        val firstRef = reference(sale)
        webhook("payment.failed", firstRef, 21_500)
        assertEquals("PAYMENT_PENDING", get("/api/v1/orders/${sale.orderId}", sale.buyer.token).json().str("status"))

        val retry = checkout(sale)
        assertEquals(HttpStatusCode.Created, retry.status)
        assertEquals(2, retry.json().long("attemptNumber"))
        assertNotEquals(firstRef, retry.json().str("checkoutUrl").substringAfterLast('/'))
    }

    @Test
    fun `an unpaid order expires after the payment window`() = gavelTest {
        val sale = sale()
        clock.advance(Duration.ofHours(23))
        runJobs()
        assertEquals("PAYMENT_PENDING", get("/api/v1/orders/${sale.orderId}", reauth(sale.buyer).token).json().str("status"))
        clock.advance(Duration.ofHours(1))
        runJobs()
        val buyer = reauth(sale.buyer)
        assertEquals("PAYMENT_EXPIRED", get("/api/v1/orders/${sale.orderId}", buyer.token).json().str("status"))
        assertEquals("ORDER_NOT_PAYABLE", post("/api/v1/orders/${sale.orderId}/checkout", token = buyer.token).json().code())
    }

    @Test
    fun `only the seller ships a paid order and only the buyer confirms delivery`() = gavelTest {
        val sale = sale()
        val shipment = mapOf("carrier" to "UPS", "trackingReference" to "1Z999AA10123456784")
        assertEquals("ORDER_NOT_SHIPPABLE", post("/api/v1/orders/${sale.orderId}/shipment", shipment, sale.seller.token).json().code())

        webhook("payment.succeeded", reference(sale), 21_500)
        assertEquals(HttpStatusCode.Forbidden, post("/api/v1/orders/${sale.orderId}/shipment", shipment, sale.buyer.token).status)
        assertEquals(HttpStatusCode.NotFound, post("/api/v1/orders/${sale.orderId}/shipment", shipment, sale.other.token).status)
        assertEquals(HttpStatusCode.Conflict, post("/api/v1/orders/${sale.orderId}/delivery", token = sale.buyer.token).status)

        val shipped = post("/api/v1/orders/${sale.orderId}/shipment", shipment, sale.seller.token)
        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
        assertEquals("SHIPPED", shipped.json().str("status"))
        assertEquals("UPS", shipped.json().obj("shipment").str("carrier"))

        assertEquals(HttpStatusCode.Forbidden, post("/api/v1/orders/${sale.orderId}/delivery", token = sale.seller.token).status)
        val delivered = post("/api/v1/orders/${sale.orderId}/delivery", token = sale.buyer.token)
        assertEquals("DELIVERED", delivered.json().str("status"))
    }

    @Test
    fun `suspended sellers cannot update fulfillment`() = gavelTest {
        val sale = sale()
        webhook("payment.succeeded", reference(sale), 21_500)
        post("/admin/v1/accounts/${sale.seller.id}/suspend", mapOf("operator" to "ops")) { header("Authorization", "Bearer $ADMIN_TOKEN") }
        val response = post("/api/v1/orders/${sale.orderId}/shipment", mapOf("carrier" to "UPS", "trackingReference" to "1Z999"), sale.seller.token)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `checkout requires a shipping address`() = gavelTest {
        val seller = seller()
        val buyer = registerAndVerify(shippingAddress = false)
        val id = publishedListing(seller)
        bid(id, buyer, 10_000)
        clock.advance(Duration.ofHours(2))
        runJobs()
        val b = reauth(buyer)
        val orderId = get("/api/v1/me/orders", b.token).json().arr("items").single().jsonObject.str("id")
        assertEquals("SHIPPING_ADDRESS_REQUIRED", post("/api/v1/orders/$orderId/checkout", token = b.token).json().code())
    }
}
