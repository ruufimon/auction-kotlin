# Domain Events Architecture

This document describes the domain-driven event architecture used in Gavel Auction for inter-feature communication.

## Overview

Domain events are immutable, versioned messages that represent significant state changes in the system. They enable loose coupling between features while maintaining a clear audit trail and supporting financial compliance requirements.

## Core Concepts

### EventEnvelope
All domain events are wrapped in a standard envelope:

```kotlin
data class EventEnvelope(
    val eventId: UUID,              // Unique event identifier
    val type: String,               // Event type (e.g., "catalog.auction-sold")
    val payloadVersion: Int,        // Schema version for breaking changes
    val aggregateId: String,        // The entity this event affects
    val occurredAt: Instant,        // When the event occurred (UTC)
    val correlationId: String,      // Trace across distributed operations
    val payload: String,            // JSON-encoded event data
)
```

### EventPublisher
Features publish events through a single interface:

```kotlin
interface EventPublisher {
    fun publish(type: String, payloadVersion: Int, aggregateId: String, payload: String)
}
```

**Key property:** Publishing is synchronous and happens within the same database transaction as the state change. This ensures atomicity: the event and the state change both commit or both roll back.

### EventHandler
Features that consume events implement this interface:

```kotlin
interface EventHandler {
    val name: String                // Idempotency key (for replay safety)
    val eventType: String           // Which event type to consume
    val payloadVersion: Int         // Which schema version to handle
    fun handle(event: EventEnvelope)
}
```

**Key property:** Handlers are idempotent. They can be replayed safely even if they've already processed an event.

### AuditLog
Security-sensitive and financial operations are recorded:

```kotlin
interface AuditLog {
    fun record(
        actor: Actor,               // Who initiated the action
        action: String,             // What was done
        entityType: String,         // Which entity type
        entityId: String,           // Which specific entity
        details: Map<String, String?> = emptyMap()  // Additional context
    )
}
```

## Event Communication Patterns

### 1. Catalog → Settlement: Auction Closed

**Catalog publishes:** `AUCTION_SOLD` or `AUCTION_UNSOLD`

```kotlin
// Catalog closes auction at scheduled time
// Publishes:
AuctionSoldV1(
    listingId = "list_123",
    sellerId = "user_456",
    buyerId = "user_789",
    winningBidId = "bid_999",
    winningAmountMinor = 50000,    // $500.00
    shippingPriceMinor = 1500,     // $15.00
    currency = "USD",
    closedAt = "2026-11-06T18:00:00Z"
)
```

**Settlement consumes:** Creates the order

```kotlin
// Settlement.OrderCreationSubscriber
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

### 2. Settlement → Fulfillment: Order Paid

**Settlement publishes:** `ORDER_PAID`

```kotlin
OrderPaidV1(
    orderId = "order_111",
    listingId = "list_123",
    buyerId = "user_789",
    sellerId = "user_456",
    totalMinor = 51500,  // $515.00
    currency = "USD",
    paidAt = "2026-11-06T18:15:00Z"
)
```

**Catalog/Fulfillment consumes:** Updates listing status, notifies seller

### 3. Bidding → Catalog: Bid Placed

**Bidding publishes:** `BID_PLACED`

```kotlin
BidPlacedV1(
    auctionId = "list_123",
    bidderId = "user_789",
    bidId = "bid_999",
    amountMinor = 50000,
    currency = "USD",
    sequenceNumber = 5,
    placedAt = "2026-11-06T17:45:00Z"
)
```

**Catalog consumes:** Updates highest bid display, checks for auction close

### 4. User-Onboarding Events

**Published:**
- `USER_REGISTERED` → New account created
- `EMAIL_VERIFIED` → User can now bid/sell
- `SELLER_ENABLED` → User can now publish listings
- `ACCOUNT_SUSPENDED` → Immediate lock on all operations
- `ACCOUNT_REACTIVATED` → Restore active status

**Consumers:** Bidding, Catalog, Settlement all subscribe to validate eligibility

## Versioned Payloads: Schema Evolution

Event payloads are versioned to support backward-compatible evolution:

```kotlin
object CatalogEvents {
    const val AUCTION_SOLD = "catalog.auction-sold"
}

// Current version
@Serializable data class AuctionSoldV1(...)

// Future: Add new field without breaking old consumers
@Serializable data class AuctionSoldV2(
    ... all V1 fields ...
    val reservePriceMinor: Long,   // NEW in v2
)
```

**When updating:**
1. Define new version class (e.g., `AuctionSoldV2`)
2. Update `EventPublisher.publish()` to send v2
3. Old handlers stay on v1 (they ignore v2 events)
4. Migrate handlers to v2 on their own schedule
5. After all consumers upgraded: remove v1

**Compliance:** Satisfies requirement 2.4 (breaking changes must be versioned).

## Transactional Outbox Pattern

Events are durably stored in a database outbox table before being published:

```
1. Application logic runs
2. Application publishes event → stored in outbox
3. Business state updated in same transaction
4. Transaction commits (atomically)
5. Outbox processor publishes to message bus (eventual consistency)
```

**Benefits:**
- ✅ No message loss (events persisted before commit)
- ✅ Exactly-once delivery (with idempotent handlers)
- ✅ Works without external brokers (in-process events)
- ✅ Deterministic when needed (synchronous processing)

## Idempotency & Replay Safety

EventHandler names provide idempotency keys:

```kotlin
class OrderCreationSubscriber : EventHandler {
    override val name = "settlement.order-creation"  // ← Idempotency key
    
    override fun handle(event: EventEnvelope) {
        // Safe to call multiple times with same event
        // Implementation should check if order already exists
    }
}
```

If the same `EventEnvelope` is processed twice:
1. First call: Creates order
2. Second call: No-op or updates existing order
3. Result: Always correct

**Compliance:** Satisfies requirement 5.4 SET-002 (exactly one order per closed auction).

## Forbidden Patterns

### ❌ Circular Events
```kotlin
Feature A publishes EventX
Feature B subscribes to EventX and publishes EventY
Feature A subscribes to EventY  ← FORBIDDEN!
```

**Why:** Makes dependency graph circular, breaks build-time enforcement.

**Solution:** Use event hierarchy. Catalog publishes for Settlement; Settlement never publishes for Catalog to consume.

### ❌ Async Fire-and-Forget
```kotlin
publishEvent(event)  // Returns immediately
// Handler runs in background, error is lost
```

**Why:** Violates AFA principle of navigability.

**Solution:** Synchronous event handling. Handlers run before `publish()` returns.

### ❌ Hidden Orchestration
```kotlin
// No one knows this happens
auctionService.closeAuction()  // Internally publishes 5 events
                                // Settlement reacts to them
                                // Catalog reacts to their results
```

**Why:** Agents can't trace the flow.

**Solution:** Explicit documentation. Every event subscription is code, not config.

## Event Flow Diagram

```
User-Onboarding Feature
    ↓ (publishes)
    UserRegistered → Bidding
    EmailVerified → Bidding, Catalog, Settlement
    SellerEnabled → Catalog

Product-Catalog Feature
    ↓ (publishes)
    AuctionScheduled
    AuctionActivated → Bidding
    AuctionClosed (SOLD/UNSOLD) → Settlement

Bidding Feature
    ↓ (publishes)
    BidPlaced → Catalog, Settlement
    BidRejected
    HighBidUpdated → Catalog

Settlement Feature
    ↓ (publishes)
    OrderPaid → Fulfillment, Catalog
    PaymentFailed
```

## Implementation Checklist

- [x] Core EventPublisher interface defined
- [x] Core EventHandler interface defined
- [x] AuditLog interface defined
- [x] Catalog events (AUCTION_SOLD, AUCTION_UNSOLD, LISTING_CANCELLED)
- [x] Settlement events (ORDER_PAID)
- [x] Bidding events (BID_PLACED, BID_REJECTED, HIGH_BID_UPDATED)
- [x] User-Onboarding events (USER_REGISTERED, EMAIL_VERIFIED, SELLER_ENABLED, etc.)
- [ ] Transactional outbox table (database schema)
- [ ] EventPublisher implementation (Spring @Service)
- [ ] Catalog event handlers (CatalogEventHandlers)
- [ ] Settlement event handlers (OrderCreationSubscriber, etc.)
- [ ] Bidding event handlers (depends on catalog integration)
- [ ] User-Onboarding event handlers (eligibility checks)
- [ ] Event replay/recovery mechanism
- [ ] Monitoring & alerting for event processing

## Testing Strategy

### Unit Tests
```kotlin
class OrderCreationSubscriberTest {
    @Test
    fun `should create order from AuctionSold event`() {
        val event = EventEnvelope(
            type = CatalogEvents.AUCTION_SOLD,
            payload = json.encodeToString(AuctionSoldV1(...))
        )
        
        subscriber.handle(event)
        
        verify(orderService).createOrder(...)
    }
    
    @Test
    fun `should be idempotent`() {
        subscriber.handle(event)
        subscriber.handle(event)  // Same event
        
        verify(orderService, times(1)).createOrder(...)  // Only once
    }
}
```

### Integration Tests
```kotlin
class EventIntegrationTest {
    @Test
    fun `auction closed should create order in settlement`() {
        val auction = createAndActivateAuction()
        placeBids(auction)
        
        // Close auction → triggers AuctionSold event
        val order = catalogService.closeAuction(auction.id)
        
        // Settlement should have processed it
        val createdOrder = orderService.findByAuctionId(auction.id)
        assertThat(createdOrder).isNotNull()
        assertThat(createdOrder.total).isEqualTo(winningBid + shipping)
    }
}
```

## See Also

- **AGENT.md** — Rule 6: Event-Driven Feature Communication
- **requirement.md** — Section 2.2: Auditability and state transitions
- **RESTRUCTURING.md** — How domain events support AFA structure
