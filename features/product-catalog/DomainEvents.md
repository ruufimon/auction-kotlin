# Product Catalog Feature - Domain Events

## Overview
The product catalog feature manages listings, auctions, and their lifecycles. It publishes the most events (since auctions are the central business process) and consumes events to validate state transitions.

## Events Published

### 1. AUCTION_SOLD
**When:** Auction closes with a winning bid.

**Data:**
```kotlin
AuctionSoldV1(
    listingId: String,              // Auction/listing identifier
    sellerId: String,               // Original listing creator
    buyerId: String,                // User who placed winning bid
    winningBidId: String,           // Bid that won
    winningAmountMinor: Long,       // Winning bid amount (in minor currency units)
    shippingPriceMinor: Long,       // Shipping cost (in minor currency units)
    currency: String,               // ISO 4217 currency code
    closedAt: String,               // Timestamp when auction closed
)
```

**Consumers:**
- **Settlement** — Creates order, processes payment (requirement 5.4 SET-001)
- **Bidding** (cache invalidation) — Remove auction from active auctions cache
- **User-Onboarding** (optional) — Award seller badge for completed sale
- **Audit Log** — Transaction record

**Trigger:** 
- Scheduled: Auction reaches closedAt timestamp with bids
- Manual: Seller or admin forces closure
- Never: If no bids received (publish AUCTION_UNSOLD instead)

---

### 2. AUCTION_UNSOLD
**When:** Auction closes without any winning bid.

**Data:**
```kotlin
AuctionUnsoldV1(
    listingId: String,
    sellerId: String,
    reason: String,                 // NO_BIDS | RESERVE_NOT_MET | SELLER_CANCELLED
    closedAt: String,
)
```

**Consumers:**
- **Settlement** — Ensures no order is created
- **Bidding** — Remove from active auctions
- **User-Onboarding** (optional) — Notification to seller
- **Audit Log** — Record closure reason

---

### 3. LISTING_CANCELLED
**When:** Seller cancels an active listing before auction closes.

**Data:**
```kotlin
ListingCancelledV1(
    listingId: String,
    sellerId: String,
    reason: String,                 // SELLER_REQUEST | ADMIN_REMOVAL | POLICY_VIOLATION
    cancelledAt: String,
)
```

**Consumers:**
- **Bidding** — Reject any new bids, remove from active
- **Settlement** — Clean up any pending orders
- **Audit Log** — Record cancellation reason

---

### 4. AUCTION_ACTIVATED (optional)
**When:** Listing transitions from draft to active (ready for bids).

**Data:**
```kotlin
AuctionActivatedV1(
    listingId: String,
    sellerId: String,
    startingBidMinor: Long,
    reservePriceMinor: Long,        // Minimum winning bid
    closesAt: String,               // When auction will close
    currency: String,
    activatedAt: String,
)
```

**Consumers:**
- **Bidding** — Add to active auctions cache (needed for BID_PLACED validation)
- **User-Onboarding** (optional) — Mark seller as active lister

---

### 5. AUCTION_DEACTIVATED (optional)
**When:** Auction is disabled but not yet closed (paused, admin action).

**Data:**
```kotlin
AuctionDeactivatedV1(
    listingId: String,
    sellerId: String,
    reason: String,                 // ADMIN_REVIEW | SUSPICIOUS_ACTIVITY | SELLER_REQUEST
    deactivatedAt: String,
)
```

**Consumers:**
- **Bidding** — Remove from active auctions, reject new bids

---

## Events Consumed

### From Bidding
**BID_PLACED**
- **Why:** Need to track highest bid for display and auction close logic
- **Handler:** `BidTrackerSubscriber`
  ```kotlin
  class BidTrackerSubscriber(private val auctionService: AuctionService) : EventHandler {
      override val name = "catalog.bid-tracking"
      override val eventType = BiddingEvents.BID_PLACED
      override val payloadVersion = 1
      
      override fun handle(event: EventEnvelope) {
          val payload = json.decodeFromString<BidPlacedV1>(event.payload)
          auctionService.recordBid(
              auctionId = payload.auctionId,
              bidderId = payload.bidderId,
              amount = payload.amountMinor
          )
          // Triggers check: if this is last bid before close time, might schedule auto-close
      }
  }
  ```

**HIGH_BID_UPDATED**
- **Why:** Update UI display with current highest bidder/amount
- **Handler:** `HighBidDisplayUpdater` - Caches for fast reads

---

### From Settlement
**ORDER_PAID**
- **Why:** Update listing status to SOLD, mark auction as completed (informational)
- **Handler:** `OrderPaidListener`
  ```kotlin
  class OrderPaidListener(private val listingService: ListingService) : EventHandler {
      override val name = "catalog.order-paid-confirmation"
      override val eventType = SettlementEvents.ORDER_PAID
      override val payloadVersion = 1
      
      override fun handle(event: EventEnvelope) {
          val payload = json.decodeFromString<OrderPaidV1>(event.payload)
          // Set listing.status = SOLD, listing.completedAt = now()
          listingService.markSold(
              listingId = payload.listingId,
              finalPrice = payload.totalMinor
          )
      }
  }
  ```

**PAYMENT_FAILED**
- **Why:** Re-open auction if payment fails (buyer defaults)
- **Handler:** `PaymentFailureHandler` - Unlocks auction for new bids

---

### From User-Onboarding
**SELLER_ENABLED**
- **Why:** User can now create listings (requirement 5.2 CAT-002)
- **Handler:** `SellerEligibilityTracker` - Caches which users can sell
- **Check:** `createListing()` validates seller is enabled

**ACCOUNT_SUSPENDED**
- **Why:** Suspended seller's listings become inactive immediately
- **Handler:** `SuspensionEnforcer` - Auto-deactivates all listings by suspended user

---

## Listing State Machine

```
         createListing(draft)
              ↓
    ┌─────────────────────┐
    │  LISTING_DRAFT      │
    │  (not visible)      │
    └─────────────────────┘
              ↓
         publishListing()
              ↓ 
    ┌─────────────────────────────┐
    │  AUCTION_ACTIVATED event    │
    │  LISTING_ACTIVE             │
    │  (bids accepted)            │
    └─────────────────────────────┘
              ↓
    [scheduled: closedAt time] OR [receiving bids]
              ↓
    ┌─────────────────────────────┐
    │  AUCTION_SOLD event         │ ← Has winner & paid
    │  or AUCTION_UNSOLD event    │ ← No winner or closed
    │  LISTING_SOLD/UNSOLD        │
    └─────────────────────────────┘

    OR (anytime):
              ↓
    [seller calls cancelListing()]
              ↓
    ┌─────────────────────────────┐
    │  LISTING_CANCELLED event    │
    │  LISTING_CANCELLED          │
    └─────────────────────────────┘
```

---

## Auction Scheduling & Close Logic

### Background Job
```kotlin
class AuctionCloserJob(
    private val auctionRepository: AuctionRepository,
    private val publisher: EventPublisher
) {
    fun closeExpiredAuctions() {
        val expiredAuctions = auctionRepository.findByClosesAtBefore(now())
        
        for (auction in expiredAuctions) {
            val highestBid = auctionRepository.findHighestBid(auction.id)
            
            if (highestBid != null) {
                // Auction has winner
                publisher.publish(
                    type = CatalogEvents.AUCTION_SOLD,
                    payloadVersion = 1,
                    aggregateId = auction.id,
                    payload = json.encodeToString(AuctionSoldV1(...))
                )
            } else {
                // No bids, unsold
                publisher.publish(
                    type = CatalogEvents.AUCTION_UNSOLD,
                    payloadVersion = 1,
                    aggregateId = auction.id,
                    payload = json.encodeToString(AuctionUnsoldV1(...))
                )
            }
        }
    }
}
```

**Trigger:** 
- Runs every minute (configurable)
- Checked when listing publishes (if closesAt is now/past)

---

## Forbidden Patterns

❌ **Catalog must NOT subscribe to settlement events for state updates**
- Settled via ORDER_PAID event only (informational)
- Catalog does NOT wait for payment to close auction
- Auction closes immediately on HIGH_BID_UPDATED when time reached

❌ **Catalog must NOT publish events that settlement uses for business logic**
- Exception: AUCTION_SOLD (settlement depends on this for order creation)
- But: Settlement doesn't publish events for catalog's product updates

❌ **Direct feature imports**
```kotlin
// ❌ FORBIDDEN
import com.gavel.settlement.OrderService  // Direct import!
val order = orderService.findByAuctionId(auctionId)

// ✅ CORRECT
// Wait for ORDER_PAID event
```

---

## Inventory & Concurrent Bids

### Problem: What if two bids arrive simultaneously?

```
Time T:    Bid A (amount 50) and Bid B (amount 51) both arrive
           
Database:  Uses row-level lock on auction row
           - First to acquire lock: Bid B (51) succeeds
           - Second: Bid A (50) fails validation (too low)
           
Result:    Exactly one highest bid per auction (guaranteed by DB)
           Events publish in order: BID_PLACED(51), BID_REJECTED(50)
```

**Mechanism:**
```sql
BEGIN TRANSACTION;
  SELECT * FROM auctions WHERE id = ? FOR UPDATE;  -- Exclusive lock
  SELECT MAX(amount) FROM bids WHERE auctionId = ?;
  IF newBid.amount > currentMax:
    INSERT INTO bids (...)
    PUBLISH BID_PLACED event
  ELSE:
    PUBLISH BID_REJECTED event
COMMIT;
```

---

## Compliance & Testing

### Requirement 5.3: CAT-001 through CAT-005
- **CAT-001**: Auction can be created, activated, closed ✓ (state machine)
- **CAT-002**: Seller must be verified ✓ (SELLER_ENABLED gate)
- **CAT-003**: Auction details persist ✓ (database + events for audit)
- **CAT-004**: Auction close enforced by scheduler ✓ (AuctionCloserJob)
- **CAT-005**: Bid acceptance validation ✓ (BidPlacedValidator checks listing is active)

### Requirement 5.4: SET-001 and SET-002
- **SET-001**: Settlement notified of closed auction ✓ (AUCTION_SOLD event)
- **SET-002**: Exactly one order per auction ✓ (event idempotency + DB unique constraint)

### Unit Tests
- [x] Listing state transitions (draft → active → sold/unsold/cancelled)
- [x] Auction close logic (with/without bids, reserve price)
- [x] Bid validation (minimum increment, timing)
- [x] Event publishing for all state changes

### Integration Tests
- [x] Complete auction flow: create → activate → receive bids → close → AUCTION_SOLD published → ORDER_PAID → marked sold
- [x] No winner: AUCTION_UNSOLD published, no order created
- [x] Seller cancels: LISTING_CANCELLED published, reject new bids
- [x] Concurrent bids: Only highest wins, others rejected
- [ ] Large auction volume (100s closing simultaneously)

---

## See Also
- **DOMAIN_EVENTS.md** — Core event architecture and patterns
- **requirement.md** — Section 5.3 (Catalog requirements)
- **RESTRUCTURING.md** — Feature organization
