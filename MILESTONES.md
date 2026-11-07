# API Implementation Plan - 7 GitHub Milestones

**Status:** Planning Complete ✅  
**Created:** 2026-11-06  
**GitHub Milestones:** https://github.com/ruufimon/auction-kotlin/milestones  
**Details:** See session artifacts or [api-implementation-plan.md](./docs/API_IMPLEMENTATION_PLAN.md)

## 7 Sequential Milestones

All milestones are now created and tracked on GitHub. This document provides a quick overview.

### M1: Database Schema & Authentication
- **Week:** 1
- **Focus:** Infrastructure foundation
- **Deliverables:** PostgreSQL schema, Flyway migrations, JWT config, test database
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/1

### M2: User-Onboarding Complete
- **Week:** 1-2
- **Focus:** Authentication feature
- **Deliverables:** Keycloak realm as code (Google + email/password), token validation, account bootstrap, suspension, profile, credit eligibility, seller-enable
- **Dependencies:** M1
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/2

### M3: Catalog API & UI
- **Week:** 2-3
- **Focus:** Listing and auction management
- **Deliverables:** 8 catalog endpoints (create, update, mine, publish, cancel, detail, search, images), activation/closing scheduler jobs, marketplace/detail/seller UI
- **Plan:** [docs/M3_CATALOG_PLAN.md](./docs/M3_CATALOG_PLAN.md)
- **Dependencies:** M2
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/3

### M4: Bidding Complete
- **Week:** 3
- **Focus:** Bidding with concurrency control
- **Deliverables:** 4 bid endpoints with validation, locking, idempotency
- **Dependencies:** M3
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/4

### M5: Settlement Complete
- **Week:** 3-4
- **Focus:** Payment and order fulfillment
- **Deliverables:** 6 settlement endpoints (orders, checkout, webhook, shipment, delivery)
- **Dependencies:** M4
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/5

### M6: Scheduling & Event Delivery
- **Week:** 4-5
- **Focus:** Infrastructure for asynchronous communication
- **Deliverables:** Event outbox, publisher, handlers, scheduler jobs
- **Dependencies:** M5
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/6

### M7: Production Ready
- **Week:** 5+
- **Focus:** Testing, security, deployment
- **Deliverables:** Integration tests, load testing, DigitalOcean deployment
- **Dependencies:** M6
- **Status:** Open
- **URL:** https://github.com/ruufimon/auction-kotlin/milestone/7

## API Endpoints Summary

**Total: 22 application endpoints across 4 features, plus Keycloak's OIDC endpoints for identity**

### User-Onboarding (4 application endpoints + Keycloak)
Registration, email verification, sign-in (Google or email/password), token refresh and sign-out are
Keycloak's standard OpenID Connect endpoints (requirement §6.1, PR #60). The application exposes:
```
GET    /api/v1/me                                  (account bootstrap, USR-013)
GET    /api/v1/profile/{userId}
PUT    /api/v1/profile/{userId}                    (profile; credit-eligibility inputs)
POST   /api/v1/profile/{userId}/enable-seller
POST   /internal/accounts/{userId}/suspend | /reactivate   (operator, USR-010)
```

### Product-Catalog (8 endpoints)
```
POST   /api/v1/listings
PUT    /api/v1/listings/{listingId}
GET    /api/v1/listings/mine
POST   /api/v1/listings/{listingId}/publish
POST   /api/v1/listings/{listingId}/cancel
GET    /api/v1/listings/{listingId}
GET    /api/v1/listings/search
POST   /api/v1/listings/{listingId}/images   (if upload is chosen, see #54)
```
Detailed plan: [docs/M3_CATALOG_PLAN.md](./docs/M3_CATALOG_PLAN.md) — issues #46–#54 plus UI #33–#35.

### Bidding (4 endpoints)
```
POST   /api/v1/bids
GET    /api/v1/bids/my-bids
GET    /api/v1/auctions/{auctionId}/bid-history
GET    /api/v1/listings/{listingId}/bids
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

## Key Architecture Decisions

✅ **Modular Monolith:** Each feature in `features/` with api/, application/, domain/, infrastructure/ layers  
✅ **Single Writer:** Each aggregate owned by one feature  
✅ **Event-Driven:** Transactional outbox pattern for async communication  
✅ **Idempotency:** All financial commands support idempotency keys  
✅ **Concurrency:** Database row-level locking for competitive operations  
✅ **Audit Trail:** All state changes recorded  

## Next Steps

1. **Create GitHub Issues** for each task in M1
2. **Link Issues to Milestone** via GitHub UI
3. **Implement M1 tasks** (schema design, migrations, JWT config)
4. **Track Progress** on milestones page

## Resources

- **Detailed Plan:** Session workspace `/Users/ruufimon/.copilot/session-state/9865f8a9-67e5-473a-b743-0b450c14831e/api-implementation-plan.md`
- **Architecture Guide:** [AGENT.md](./AGENT.md) — 6 AFA rules
- **Event Architecture:** [DOMAIN_EVENTS.md](./DOMAIN_EVENTS.md) — Event patterns
- **Requirements:** [requirements.md](./requirements.md) — Full functional/technical requirements

---
**Status:** Ready for Implementation  
**Estimated Effort:** 175 hours (~4-5 weeks at 40 hrs/week)  
**Last Updated:** 2026-11-06
