# Full-Stack Implementation Plan - 7 GitHub Milestones

**Status:** Planning Complete ✅  
**Created:** 2026-11-06  
**GitHub Milestones:** https://github.com/ruufimon/auction-kotlin/milestones  
**Details:** See session artifacts or [api-implementation-plan.md](./docs/API_IMPLEMENTATION_PLAN.md)

## 7 Sequential Milestones

All milestones are now created and tracked on GitHub. This document provides a quick overview.

### M1: Database Schema & Authentication
- **Week:** 1
- **Focus:** Backend and web foundation
- **Deliverables:** PostgreSQL schema, Flyway migrations, JWT config, test database, web application shell, API client configuration, and shared UI primitives
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/1

### M2: User-Onboarding Complete
- **Week:** 1-2
- **Focus:** Authentication and onboarding experience
- **Deliverables:** 8 auth endpoints (register, verify, signin, refresh, signout, profile, seller-enable); registration, email verification, sign-in, profile, credit-eligibility, and seller-enable UI flows
- **Dependencies:** M1
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/2

### M3: Catalog API & UI
- **Week:** 2-3
- **Focus:** Listing and auction management experience
- **Deliverables:** 8 catalog endpoints (create, update, publish, cancel, search, scheduler); marketplace discovery, search/filter, listing-detail, and seller listing-creation UI
- **Dependencies:** M2
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/3

### M4: Bidding Complete
- **Week:** 3
- **Focus:** Bidding with concurrency control and feedback
- **Deliverables:** 4 bid endpoints with validation, locking, idempotency; authenticated bidding form, bid history, current-price updates, and clear acceptance/rejection states
- **Dependencies:** M3
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/4

### M5: Settlement Complete
- **Week:** 3-4
- **Focus:** Payment and order-fulfillment experience
- **Deliverables:** 6 settlement endpoints (orders, checkout, webhook, shipment, delivery); buyer order and checkout UI plus seller fulfillment and shipment-tracking UI
- **Dependencies:** M4
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/5

### M6: Scheduling & Event Delivery
- **Week:** 4-5
- **Focus:** Reliable asynchronous behavior and UI state refresh
- **Deliverables:** Event outbox, publisher, handlers, scheduler jobs; web handling for auction, payment, and fulfillment state refreshes and recoverable error states
- **Dependencies:** M5
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/6

### M7: Production Ready
- **Week:** 5+
- **Focus:** Full-stack testing, security, and deployment
- **Deliverables:** API integration tests, browser end-to-end tests for critical journeys, accessibility and responsive UI checks, load testing, and DigitalOcean deployment
- **Dependencies:** M6
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/7

## API Endpoints Summary

**Total: 26 endpoints across 4 features**

### User-Onboarding (8 endpoints)
```
POST   /api/v1/auth/register
POST   /api/v1/auth/verify-email
POST   /api/v1/auth/signin
POST   /api/v1/auth/refresh
POST   /api/v1/auth/signout
GET    /api/v1/profile/{userId}
PUT    /api/v1/profile/{userId}
POST   /api/v1/profile/{userId}/enable-seller
```

### Product-Catalog (8 endpoints)
```
POST   /api/v1/listings
PUT    /api/v1/listings/{listingId}
POST   /api/v1/listings/{listingId}/publish
POST   /api/v1/listings/{listingId}/cancel
GET    /api/v1/listings/{listingId}
GET    /api/v1/listings/search
GET    /api/v1/listings/{listingId}/bids
```

### Bidding (4 endpoints)
```
POST   /api/v1/bids
GET    /api/v1/bids/my-bids
GET    /api/v1/auctions/{auctionId}/bid-history
```

### Settlement (6 endpoints)
```
GET    /api/v1/orders
GET    /api/v1/orders/{orderId}
POST   /api/v1/orders/{orderId}/checkout
POST   /api/v1/orders/{orderId}/shipment
POST   /api/v1/orders/{orderId}/delivery
POST   /webhook/payment
```

## Web UI Summary

The web app is delivered alongside the API rather than as a final integration
phase. Its primary user journeys are:

1. **Onboarding:** registration, email verification, credit eligibility,
   sign-in, profile management, and seller enablement.
2. **Marketplace:** auction discovery, filtering, listing detail, bid
   placement, and bid-result feedback.
3. **Seller workflow:** listing creation, editing, publication, and
   fulfillment updates.
4. **Settlement:** buyer order history and checkout, plus seller shipment and
   delivery-status updates.

## Key Architecture Decisions

✅ **Modular Monolith:** Each feature in `features/` with api/, application/, domain/, infrastructure/ layers  
✅ **Web UI:** Frontend work follows the same domain boundaries and consumes only versioned public API contracts
✅ **Single Writer:** Each aggregate owned by one feature  
✅ **Event-Driven:** Transactional outbox pattern for async communication  
✅ **Idempotency:** All financial commands support idempotency keys  
✅ **Concurrency:** Database row-level locking for competitive operations  
✅ **Audit Trail:** All state changes recorded  

## Next Steps

1. **Create GitHub Issues** for each remaining backend and web task
2. **Link Issues to Milestone** via GitHub UI
3. **Implement M1 tasks** (schema design, migrations, JWT config, web shell)
4. **Track Progress** on milestones page

## Resources

- **Detailed Plan:** Session workspace `/Users/ruufimon/.copilot/session-state/9865f8a9-67e5-473a-b743-0b450c14831e/api-implementation-plan.md`
- **Architecture Guide:** [AGENT.md](./AGENT.md) — 6 AFA rules
- **Event Architecture:** [DOMAIN_EVENTS.md](./DOMAIN_EVENTS.md) — Event patterns
- **Requirements:** [requirement.md](./requirement.md) — Full functional/technical requirements

---
**Status:** Ready for Implementation  
**Estimated Effort:** Re-estimate after defining the web component inventory and browser test strategy.
**Last Updated:** 2026-11-06
