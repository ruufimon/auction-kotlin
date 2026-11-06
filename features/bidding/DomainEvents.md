# Bidding Feature - Domain Events

## Overview
The bidding feature manages bid submission, acceptance, and rejection. It publishes events for state changes that affect other features and consumes events to validate eligibility.

## Events Published

### 1. BID_PLACED
**When:** A bid is submitted, validated, and accepted by the system.

**Data:**
```kotlin
BidPlacedV1(
    auctionId: String,        // Auction being bid on
    bidderId: String,         // User placing the bid
    bidId: String,            // Unique bid identifier
    amountMinor: Long,        // Bid amount (in minor currency units)
    currency: String,         // ISO 4217 currency code
    sequenceNumber: Long,     // Auction-local sequence (for deterministic ordering)
    placedAt: String,         // Timestamp when bid was accepted
)
```

**Consumers:**
- **Catalog** — Updates highest bid display, checks if auction should close
- **Settlement** — Tracks winning bid for order creation
- **User-Onboarding** — Awards bidding badges/history

---

### 2. BID_REJECTED
**When:** A bid is submitted but rejected for a specific reason.

**Data:**
```kotlin
BidRejectedV1(
    auctionId: String,        // Auction that rejected the bid
    bidderId: String,         // User whose bid was rejected
    amountMinor: Long,        // Amount that was rejected
    currency: String,
    reason: String,           // AUCTION_NOT_ACTIVE | AUCTION_ENDED | SELLER_CANNOT_BID | 
                              // BID_TOO_LOW | ACCOUNT_NOT_ELIGIBLE
    rejectedAt: String,       // Timestamp of rejection
)
```

**Note:** Rejection does not change any auction state. It's informational for the bidder and audit log.

**Consumers:**
- **User-Onboarding** — May trigger eligibility notifications
- **Audit Log** — Financial/security compliance record

---

### 3. HIGH_BID_UPDATED
**When:** A newly accepted bid becomes the highest bid for an auction.

**Data:**
```kotlin
HighBidUpdatedV1(
    auctionId: String,              // Auction with new high bid
    previousHighBidderId: String?,  // User who had previous high bid (null if first)
    previousHighAmountMinor: Long,  // Amount of previous high (0 if first)
    newHighBidderId: String,        // User with new high bid
    newHighAmountMinor: Long,       // Amount of new high bid
    currency: String,
    updatedAt: String,              // Timestamp
)
```

**Consumers:**
- **Catalog** — Updates UI display for live auction view
- **User-Onboarding** — Notifications to previous high bidder, new high bidder

---

## Events Consumed

### From User-Onboarding
**EMAIL_VERIFIED**
- **Why:** Can only bid after email verification (requirement 5.1 USR-004)
- **Handler:** `UserVerificationValidator` checks eligibility before accepting bid

**ACCOUNT_SUSPENDED**
- **Why:** Suspended accounts cannot bid (requirement 5.1 USR-010)
- **Handler:** `AccountSuspensionEnforcer` rejects all bid attempts

**SELLER_ENABLED** (optional)
- **Why:** May restrict self-bidding to sellers only
- **Handler:** Tracks which users are sellers for BID_SELLER_CANNOT_BID check

### From Product-Catalog
**AUCTION_ACTIVATED**
- **Why:** Can only bid on active auctions (requirement 5.3 BID-001)
- **Handler:** `AuctionStatusTracker` maintains cache of active auction IDs

**AUCTION_UNSOLD**
- **Why:** No more bids can be accepted (requirement 5.3 BID-001)
- **Handler:** `AuctionStatusTracker` marks auction as closed

---

## Forbidden Patterns

❌ **Bidding must NOT subscribe to Settlement events**
- Would create circular dependency: Bidding → Settlement → Bidding
- Settlement should listen to bids, not bids listen to orders

❌ **Bidding must NOT directly import Catalog features**
- Must use AuctionActivated event instead of calling catalogService.getAuction()
- Enforced by Gradle (catalog-api not in bidding/build.gradle)

❌ **Async fire-and-forget publishing**
- Event handlers must run synchronously
- If handler fails, transaction rolls back

---

## Example: Complete Bid Flow

```
1. Bidder submits bid
   → bidding.placeBid(auctionId, bidderId, amountMinor)

2. Bidding validates:
   ✓ Auction is ACTIVE (via cached AuctionStatusTracker)
   ✓ Bidder email is verified (via cached UserVerificationValidator)
   ✓ Bidder is not seller (via cached SellerTracker)
   ✓ Bid exceeds minimum increment (via auction.currentHighBid)
   ✓ Bidder is not suspended (via cached SuspensionTracker)

3. Bid is accepted
   → EventPublisher.publish("bidding.bid-placed", ...)
   
4. Synchronous handlers run:
   ✓ Catalog.HighBidTracker updates highest bid in auction
   ✓ Settlement.BidTracker records for eventual order creation
   ✓ UserOnboarding.BiddingBadgeAwarder increments bid count
   
5. Transaction commits (event + auction state together)

6. Response returned to bidder with confirmation
```

---

## Testing

### Unit Tests
- [x] Bid validation (price, increment, timing)
- [x] Seller cannot bid check
- [x] Account eligibility validation
- [x] Idempotent bid resubmission (same idempotency key)

### Integration Tests  
- [x] Bid placed event triggers catalog update
- [x] Bid rejected event is logged correctly
- [x] High bid updated event fires on every new high bid
- [ ] Concurrent bids are serialized (database lock test)
- [ ] Bid handler replay is idempotent

### Event Flow Tests
- [x] User email verified → can now place bids
- [x] Account suspended → bids rejected (ACCOUNT_NOT_ELIGIBLE)
- [x] Auction deactivated → bids rejected (AUCTION_NOT_ACTIVE)

---

## See Also
- **DOMAIN_EVENTS.md** — Core event architecture and patterns
- **requirement.md** — Section 5.3 (Bidding requirements)
- **RESTRUCTURING.md** — Feature organization
