# API Implementation Plan - 7 Milestones

**Status:** Planning Complete  
**Created:** 2026-11-06  
**GitHub Milestones:** https://github.com/ruufimon/auction-kotlin/milestones

This document tracks the phased implementation of all REST APIs for the auction platform, organized into 7 sequential milestones with clear dependencies.

## Milestone Overview

| # | Title | Timeline | Phase | Dependencies |
|---|-------|----------|-------|--------------|
| 1 | Database Schema & Authentication | Week 1 | Infrastructure | None |
| 2 | User-Onboarding Complete | Week 1-2 | Feature | M1 |
| 3 | Catalog API | Week 2-3 | Feature | M2 |
| 4 | Bidding Complete | Week 3 | Feature | M3 |
| 5 | Settlement Complete | Week 3-4 | Feature | M4 |
| 6 | Scheduling & Event Delivery | Week 4-5 | Infrastructure | M5 |
| 7 | Production Ready | Week 5+ | Deployment | M6 |

## M1: Database Schema & Authentication

**Timeline:** Week 1  
**Deliverables:**
- PostgreSQL schema for all 4 features
- Flyway migration scripts (V1__*.sql)
- JWT configuration (RS256, token expiry, refresh tokens)
- Test database with TestContainers setup

**Key Tables:**
- `users` (id, email, password_hash, display_name, is_seller, seller_info, created_at)
- `tokens` (id, user_id, type, token, expires_at)
- `listings` (id, seller_id, title, description, start_price, auction_state, created_at)
- `bids` (id, auction_id, bidder_id, amount, created_at, idempotency_key)
- `orders` (id, auction_id, buyer_id, seller_id, status, created_at)
- `audit_log` (id, entity_type, entity_id, action, actor_id, timestamp)
- `outbox` (id, aggregate_id, event_type, payload, published_at)

**Acceptance Criteria:**
- `./gradlew test` passes
- Test user can register and login
- Schema verified with integration tests

**Dependencies:** None (foundational)

## M2: User-Onboarding Complete

**Timeline:** Week 1-2  
**API Endpoints:** 8 total
- `POST /api/v1/auth/register` - Create account (USR-001)
- `POST /api/v1/auth/verify-email` - Verify email token (USR-003/004)
- `POST /api/v1/auth/signin` - Login, return JWT (USR-005)
- `POST /api/v1/auth/refresh` - Refresh access token
- `POST /api/v1/auth/signout` - Invalidate tokens (USR-010)
- `GET /api/v1/profile/{userId}` - Get user profile (USR-007)
- `PUT /api/v1/profile/{userId}` - Update profile
- `POST /api/v1/profile/{userId}/enable-seller` - Enable seller mode (USR-006/008)

**Layers:**
- **api/** Commands: RegisterCommand, VerifyEmailCommand, SignInCommand; DTOs: RegisterRequest, SignInRequest
- **application/** Services: RegisterService, AuthService, SellerEnablementService
- **domain/** User aggregate, Password policy (Argon2id), Token validation
- **infrastructure/** UserRepository, EmailService, PasswordHasher, TokenStore
- **Ktor** Routes: Authentication middleware, error handling (invalid email, weak password, duplicate user)

**Acceptance Criteria:**
- All 8 endpoints functional
- Email verification working (token-based)
- Seller profile completion workflow
- Token refresh mechanism
- E2E: register → verify → seller-enable → can list

**Dependencies:** M1

## M3: Catalog API

**Timeline:** Week 2-3  
**API Endpoints:** 8 total
- `POST /api/v1/listings` - Create listing (CAT-001/002)
- `PUT /api/v1/listings/{listingId}` - Update draft listing (CAT-005)
- `POST /api/v1/listings/{listingId}/publish` - Publish listing, start auction (CAT-006)
- `POST /api/v1/listings/{listingId}/cancel` - Cancel listing (CAT-009)
- `GET /api/v1/listings/{listingId}` - Get listing details (CAT-002)
- `GET /api/v1/listings/search` - Search with filters & pagination (CAT-007/008)
- `GET /api/v1/listings/{listingId}/bids` - Public bid history
- Scheduler: Activate scheduled listings & close expired auctions (CAT-010)

**Layers:**
- **api/** Commands: CreateListingCommand, PublishListingCommand; Queries: SearchListingsQuery; DTOs
- **application/** Services: ListingService, AuctionClosingService, DiscoveryService
- **domain/** Listing aggregate, Auction state machine (draft → active → closed → settled)
- **infrastructure/** ListingRepository, ImageStore, AuctionCloserScheduler (Quartz)
- **Ktor** Routes: Pagination, filtering (price range, category, seller)

**Acceptance Criteria:**
- List creation/editing working
- Publishing and cancellation working
- Search with filters and pagination
- Auction closer scheduler runs automatically
- E2E: create listing → publish → active

**Dependencies:** M2

## M4: Bidding Complete

**Timeline:** Week 3  
**API Endpoints:** 4 total
- `POST /api/v1/bids` - Place bid on active auction (BID-001 to BID-012)
- `GET /api/v1/bids/my-bids` - User's bid history (query)
- `GET /api/v1/auctions/{auctionId}/bid-history` - Public bid history (BID-010)
- Internal: Bid validation, concurrency control

**Layers:**
- **api/** Commands: PlaceBidCommand (includes idempotencyKey); Queries: GetBidsQuery; DTOs
- **application/** Services: BidService, BidValidator (auction active? outbid? amount valid?)
- **domain/** Bid aggregate, Bid validation rules, Concurrency control (pessimistic locking)
- **infrastructure/** BidRepository (with row-level locking), BidValidator rules
- **Ktor** Routes: Idempotency key handling (POST /api/v1/bids → idempotent)

**Acceptance Criteria:**
- Bid placement with validation (outbid check, auction state)
- Concurrent bid handling (no double-winners)
- Bid history queries
- Idempotency key support (can replay without creating duplicate bids)
- E2E: bid on active listing → win → create order

**Dependencies:** M3

## M5: Settlement Complete

**Timeline:** Week 3-4  
**API Endpoints:** 6 total
- `GET /api/v1/orders` - List user's orders (buyer & seller view) (SET-001)
- `GET /api/v1/orders/{orderId}` - Order details
- `POST /api/v1/orders/{orderId}/checkout` - Initiate payment (SET-004)
- `POST /api/v1/orders/{orderId}/shipment` - Record shipment (SET-008)
- `POST /api/v1/orders/{orderId}/delivery` - Confirm delivery (SET-009)
- `POST /webhook/payment` - Payment provider webhook (SET-006)

**Layers:**
- **api/** Commands: InitiateCheckoutCommand, RecordShipmentCommand; Queries: GetOrdersQuery; DTOs
- **application/** Services: OrderService, PaymentService, FulfillmentService
- **domain/** Order aggregate, Payment state machine (pending → paid → shipped → delivered), Order rules
- **infrastructure/** OrderRepository, PaymentGateway adapter (Stripe/PayPal), WebhookValidator, OutboxPublisher
- **Ktor** Routes: Webhook signature verification, order access control (buyer/seller only)

**Acceptance Criteria:**
- Order management working (buyer → seller view)
- Checkout initiation (cart → order → payment URL)
- Payment webhook signature verification
- Shipment/delivery tracking with status updates
- E2E: payment webhook → order marked paid → can ship → delivery confirmation

**Dependencies:** M4

## M6: Scheduling & Event Delivery

**Timeline:** Week 4-5  
**Deliverables:**
- Event outbox table and processor
- Event publisher implementation (atomic event publish + state change)
- Event handlers wired (consume from outbox)
- Scheduler jobs (activate listings, close auctions, expire orders)
- Transactional outbox pattern (guarantees durability)

**Key Components:**
- **Outbox Processor:** Poll outbox table, publish events to handlers, mark as delivered (at-least-once)
- **Event Publisher:** Transactional - event insert + aggregate update in same DB transaction
- **Event Handlers:** Idempotent (via eventId), consume domain events from all features
- **Scheduler Jobs:** Quartz jobs for listing activation, auction close, order expiration

**Events Published:**
- `catalog.auction-published` → Setup bid processing window
- `catalog.auction-closed` → Create order for winner
- `settlement.order-paid` → Notify seller to ship
- `settlement.shipment-recorded` → Notify buyer
- `bidding.bid-placed` → Validate no double-winners

**Acceptance Criteria:**
- Events published and delivered atomically
- Auctions close automatically after expiry
- Listings activate on scheduled date
- Orders expire after payment timeout
- No lost events (durability via outbox table)
- Event replay is safe (idempotent handlers)

**Dependencies:** M5

## M7: Production Ready

**Timeline:** Week 5+  
**Deliverables:**
- End-to-end integration tests (full auction flow)
- Load testing (100+ concurrent bidders)
- Security hardening (auth, input validation, HTTPS, CSRF)
- API documentation (OpenAPI/Swagger)
- Deployment to DigitalOcean App Platform
- Monitoring & logging (structured logs, Prometheus metrics)

**Key Tests:**
- E2E: User registers → seller enables → lists item → buyer bids → auction closes → payment → shipment → delivery
- Concurrent bidding: 100+ bids on same auction
- Idempotency: Duplicate payment webhooks don't create duplicate orders
- Event replay: Lost event handler can be replayed safely

**Acceptance Criteria:**
- Full auction flow works end-to-end
- 100+ concurrent bidders handled correctly
- Security review passed (OWASP Top 10)
- Deployed and running on DigitalOcean
- All smoke tests passing (health checks, basic CRUD)

**Dependencies:** M6

## Architecture Decisions

### Modular Monolith
Each feature in `features/{name}` with layers:
```
features/user-onboarding/
├── api/              # Commands, queries, DTOs, events
├── application/      # Use case orchestration services
├── domain/           # Aggregates, business rules, value objects
├── infrastructure/   # Repositories, external adapters
└── test/             # Integration tests
```

### Single Writer Pattern
Each aggregate owned by one feature:
- **User** → user-onboarding feature only
- **Listing** → product-catalog feature only
- **Bid** → bidding feature only
- **Order** → settlement feature only

Other features can READ these aggregates via `api/` layer, but cannot modify.

### Event-Driven Inter-Feature Communication
Features communicate via domain events (no direct service calls):
- `bidding` feature publishes `BidPlacedEvent`
- `catalog` feature consumes it to validate auction state
- `settlement` feature publishes `OrderPaidEvent`
- `settlement` feature consumes `AuctionClosedEvent` to create orders

### Transactional Outbox Pattern
Guarantees at-least-once event delivery:
1. State change (e.g., order paid) + event insert in same transaction
2. Both commit or both roll back (atomic)
3. Outbox processor polls table, publishes to handlers
4. Mark as published after handler succeeds

### Idempotency
All financial commands support idempotency keys:
- `PlaceBidCommand(auctionId, bidderId, amount, idempotencyKey)`
- `InitiateCheckoutCommand(orderId, userId, idempotencyKey)`
- `RecordShipmentCommand(orderId, trackingNumber, idempotencyKey)`

Prevents double-charging on network retries.

### Concurrency Control
- **Bids:** Pessimistic locking (row-level lock on auction)
- **Listings:** Optimistic locking (version field)
- **Orders:** Pessimistic locking (row-level lock during payment)

## Technology Stack

- **Language:** Kotlin on JVM 21
- **Framework:** Ktor 2.x
- **Database:** PostgreSQL 17+ with Exposed ORM
- **Migration:** Flyway
- **Auth:** JWT (RS256), Argon2id password hashing
- **Serialization:** Kotlinx Serialization
- **Testing:** Kotest + TestContainers (PostgreSQL container)
- **Scheduling:** Quartz Scheduler
- **Monitoring:** Micrometer + Prometheus

## Success Criteria

✅ All 7 Milestones delivered and merged to `main`  
✅ 26 API endpoints implemented and documented  
✅ All requirements (USR, CAT, BID, SET) mapped to endpoints  
✅ 80%+ code coverage for business logic  
✅ End-to-end auction flow works (register → list → bid → close → pay → ship)  
✅ E2E tests pass  
✅ Deployed to DigitalOcean App Platform  
✅ Load test: 100+ concurrent bidders handled  

## Tracking

- **GitHub Milestones:** https://github.com/ruufimon/auction-kotlin/milestones
- **Project Board:** (optional) Link to GitHub Projects board
- **Progress:** Update milestone % complete as PRs merge
- **Blockers:** Track in GitHub Issues with `blocked` label

## References

- **AGENT.md** — AFA principles and architectural rules
- **DOMAIN_EVENTS.md** — Event architecture and patterns
- **requirements.md** — Business requirements mapping to APIs
- **RESTRUCTURING.md** — Backend module structure
- **web/RESTRUCTURING.md** — Frontend module structure

---

**Next Action:** Start M1 - Design PostgreSQL schema and create Flyway migrations.

See: https://github.com/ruufimon/auction-kotlin/milestones
