# Settlement Feature - Domain Events

## Overview
The settlement feature manages payment collection, order fulfillment coordination, and financial settlement. It consumes events from catalog and bidding features, and publishes events for fulfillment and accounting.

## Events Published

### 1. ORDER_PAID
**When:** Payment is confirmed and order transitions to fulfillment.

**Data:**
```kotlin
OrderPaidV1(
    orderId: String,          // Unique order identifier
    listingId: String,        // Auction that generated this order
    buyerId: String,          // User who won the auction
    sellerId: String,         // User who listed the item
    totalMinor: Long,         // Final total including shipping (in minor units)
    currency: String,         // ISO 4217 currency code
    paidAt: String,           // Timestamp when payment confirmed
)
```

**Consumers:**
- **Product-Catalog** — Updates listing status to SOLD, marks auction closed
- **Fulfillment** (future) — Creates shipping label, schedules delivery
- **Accounting** — Records revenue, settlement amount to seller
- **Audit Log** — Financial compliance record (requirement 2.2)

**Note:** This is the final state transition. After ORDER_PAID, seller can withdraw funds.

---

### 2. PAYMENT_FAILED
**When:** Payment attempt fails and cannot be retried automatically.

**Data:**
```kotlin
PaymentFailedV1(
    orderId: String,
    buyerId: String,
    sellerId: String,
    listingId: String,
    attemptedAmountMinor: Long,
    currency: String,
    reason: String,           // CARD_DECLINED | INSUFFICIENT_FUNDS | 
                              // EXPIRED_CARD | FRAUD_DETECTED | PAYMENT_GATEWAY_ERROR
    failedAt: String,
)
```

**Consumers:**
- **Product-Catalog** — Returns listing to ACTIVE state (auction re-opened)
- **User-Onboarding** — May notify buyer or escalate to support
- **Audit Log** — Record payment failure for investigation

---

### 3. ORDER_CREATED
**When:** Order record is created after auction closes (internal event).

**Data:**
```kotlin
OrderCreatedV1(
    orderId: String,
    auctionId: String,
    buyerId: String,
    sellerId: String,
    winningBidAmountMinor: Long,
    shippingCostMinor: Long,
    totalMinor: Long,         // winningBid + shipping
    currency: String,
    createdAt: String,
)
```

**Note:** This fires immediately when AUCTION_SOLD is received, before payment processing.

**Consumers:**
- **Accounting** — Initial order creation for billing
- **Audit Log** — Transaction start record

---

## Events Consumed

### From Product-Catalog
**AUCTION_SOLD**
- **Why:** Need to create an order when auction closes with winner (requirement 5.4 SET-001)
- **Data:** Winning bid, seller, buyer, listing details
- **Handler:** `OrderCreationSubscriber`
  ```kotlin
  class OrderCreationSubscriber(private val orderService: OrderService) : EventHandler {
      override val name = "settlement.order-creation"
      override val eventType = CatalogEvents.AUCTION_SOLD
      override val payloadVersion = 1
      
      override fun handle(event: EventEnvelope) {
          val payload = json.decodeFromString<AuctionSoldV1>(event.payload)
          orderService.createOrder(
              auctionId = payload.listingId,
              buyerId = payload.buyerId,
              sellerId = payload.sellerId,
              totalMinor = payload.winningAmountMinor + payload.shippingPriceMinor
          )
      }
  }
  ```

**AUCTION_UNSOLD**
- **Why:** No order needed if auction ends without winning bid
- **Handler:** `OrderCleanupSubscriber` - Marks any pending order as cancelled

---

### From Bidding
**BID_PLACED** (optional cache)
- **Why:** May track bid stream for payment validation
- **Handler:** `HighBidTracker` - Maintains current highest bid for fraud checks

---

## Order State Machine

```
         AUCTION_SOLD
              ↓
    ┌─────────────────────┐
    │  ORDER_CREATED      │ ← Internal event fires
    │  (payment pending)   │
    └─────────────────────┘
              ↓
    Attempt payment →
              ↓
    ┌─────────────────────┐
    │  PAYMENT_SUCCEEDED  │
    │  ORDER_PAID event   │
    │  (can ship now)     │
    └─────────────────────┘
              ↓
         [Fulfillment handles from here]


    OR (on failure):
              ↓
    ┌─────────────────────┐
    │  PAYMENT_FAILED     │ ← Publish PAYMENT_FAILED
    │  (retry or cancel)  │
    └─────────────────────┘
              ↓
    Catalog re-opens auction
```

---

## Forbidden Patterns

❌ **Settlement must NOT subscribe to bidding events directly**
- Bids flow through catalog (catalog publishes HighBidUpdated)
- Settlement listens to catalog state, not bid stream
- Enforced: bidding-api not in settlement/build.gradle

❌ **Settlement must NOT publish events that catalog consumes for product updates**
- Order state is query-only for catalog
- Catalog drives state through closeAuction, not from settlement events
- Exception: ORDER_PAID is informational only

❌ **Settlement publishing events that bidding cares about**
- Would create circular dependency
- Bidding doesn't care about payment state

---

## Payment Processing

### Credit Card Payment
```kotlin
class CreditCardPaymentProcessor(
    private val paymentGateway: PaymentGatewayClient,
    private val publisher: EventPublisher,
    private val auditLog: AuditLog
) {
    fun processPayment(order: Order): Result<Unit> {
        try {
            val charge = paymentGateway.charge(
                amount = order.totalMinor,
                currency = order.currency,
                customerId = order.buyerId,
                metadata = mapOf("orderId" to order.id)
            )
            
            // Publish ORDER_PAID event
            publisher.publish(
                type = SettlementEvents.ORDER_PAID,
                payloadVersion = 1,
                aggregateId = order.id,
                payload = json.encodeToString(OrderPaidV1(
                    orderId = order.id,
                    listingId = order.auctionId,
                    buyerId = order.buyerId,
                    sellerId = order.sellerId,
                    totalMinor = order.totalMinor,
                    currency = order.currency,
                    paidAt = Instant.now().toString()
                ))
            )
            
            auditLog.record(
                actor = Actor.System,
                action = "PAYMENT_PROCESSED",
                entityType = "Order",
                entityId = order.id,
                details = mapOf("chargeId" to charge.id)
            )
            
            return Result.success(Unit)
        } catch (e: PaymentGatewayException) {
            // Publish PAYMENT_FAILED event
            publisher.publish(...)
            auditLog.record(
                actor = Actor.System,
                action = "PAYMENT_FAILED",
                entityType = "Order",
                entityId = order.id,
                details = mapOf("reason" to e.reason)
            )
            return Result.failure(e)
        }
    }
}
```

---

## Compliance & Auditability

### Requirement 2.2: Auditability
- Every order creation logged via ORDER_CREATED event
- Every payment attempt logged (success or failure)
- Actor (buyer) recorded via audit log
- Timestamp captured in events (occurredAt)
- Immutable event log provides audit trail

### Requirement 2.4: Schema Evolution
- Events are versioned (OrderPaidV1, future OrderPaidV2)
- Adding new fields: create V2, handlers choose when to migrate
- Removing fields: Never (use V2 with deprecated fields null)

### Requirement 5.4: SET-002
- **Exactly one order per closed auction**
- Idempotent handler: `OrderCreationSubscriber.name` is idempotency key
- If AUCTION_SOLD replayed: Check if order exists, return early
- Database unique constraint: `UNIQUE(auctionId)` on orders table

---

## Testing

### Unit Tests
- [x] Order creation from valid AUCTION_SOLD event
- [x] Order creation is idempotent (same event twice = one order)
- [x] Payment processing success → ORDER_PAID published
- [x] Payment processing failure → PAYMENT_FAILED published
- [x] Order total calculation (bid + shipping)
- [x] Currency validation

### Integration Tests
- [x] Auction close flow: catalog publishes AUCTION_SOLD → settlement creates order → publishes ORDER_PAID
- [x] Payment retry: PAYMENT_FAILED → settlement retries → ORDER_PAID succeeds
- [x] Duplicate event handling: AUCTION_SOLD replayed after 10 seconds → no duplicate order
- [x] Concurrent order creation (stress test): multiple auctions close simultaneously

### Event Flow Tests
- [x] ORDER_CREATED fires immediately after AUCTION_SOLD
- [x] ORDER_PAID fires only after successful payment
- [x] PAYMENT_FAILED triggers catalog to re-open auction
- [x] Audit log records all state transitions

---

## Seller Settlement (Future)

When ORDER_PAID fires, seller can eventually withdraw their portion:
```
ORDER_PAID event
    ↓ [async settlement process]
    ↓ (calculate seller share, deduct platform fees)
    ↓
Settlement.withdraw(sellerId, amount)
    ↓
SELLER_PAYOUT event (future feature)
```

This is event-driven but async/eventually consistent.

---

## See Also
- **DOMAIN_EVENTS.md** — Core event architecture and patterns
- **requirement.md** — Section 5.4 (Settlement requirements)
- **RESTRUCTURING.md** — Feature organization
