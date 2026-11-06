# Requirement traceability

Maps `requirement.md` identifiers to the code and tests that cover them.
Integration tests live in `app/src/test/kotlin/com/gavel/app`; domain unit tests
sit next to each module.

## User onboarding

| Requirement | Covered by |
|---|---|
| USR-001 register with unique normalized email | `OnboardingTest`: registration creates a pending account…, validates fields and rejects duplicate emails |
| USR-002 password policy, memory-hard hashing | `OnboardingRulesTest` (policy, Argon2id); `Argon2PasswordHasher` |
| USR-003 single-use expiring verification token | `OnboardingTest`: a verification token works once and fails after use or expiry |
| USR-004 verify before bidding or publishing | `OnboardingTest` (pending cannot sign in); `CatalogTest`/`BiddingTest` eligibility checks |
| USR-005 short-lived JWT plus rotating refresh token | `OnboardingTest`: refresh rotates tokens… |
| USR-006 hashed refresh tokens, reuse and suspension invalidate | `OnboardingTest`: refresh rotates…and reuse revokes the family; sign out revokes; suspension blocks refresh |
| USR-007 profile, contact and shipping details | `OnboardingTest`: profile updates validate input and honour If-Match |
| USR-008 seller eligibility from required fields | `OnboardingTest`: completing the seller profile enables selling; `CatalogTest`: only sellers with a complete profile… |
| USR-009 one account is buyer and seller | `OnboardingTest`: …enables selling on the same account |
| USR-010 suspension takes effect immediately | `OnboardingTest`: suspension blocks…; `BiddingTest`; `SettlementTest`: suspended sellers cannot update fulfillment |

## Product catalog

| Requirement | Covered by |
|---|---|
| CAT-001/002 eligible sellers create drafts with all listing fields | `CatalogTest`: only sellers with a complete profile…; `ListingRulesTest` |
| CAT-003 schedule limits | `CatalogTest`: schedules must end after they start…; `ListingRulesTest` |
| CAT-004 price rules | `CatalogTest`: invalid prices and missing fields prevent publication; `ListingRulesTest` |
| CAT-005 edit rules by status | `CatalogTest`: active listings lock economic fields… |
| CAT-006 publish to SCHEDULED or ACTIVE | `CatalogTest`: DRAFT to SCHEDULED to ACTIVE…; publishing…activates immediately |
| CAT-007/008 public search, filters, stable pagination, sorting | `CatalogTest`: search filters, sorts and paginates stably; `HttpContractTest`: page sizes |
| CAT-009 cancel only before the first bid | `CatalogTest`: listings without bids can be cancelled… |
| CAT-010 SOLD/UNSOLD at end time | `CatalogTest`: closing produces SOLD…exactly once |
| Retry-safe lifecycle jobs | `CatalogTest` (jobs re-run without changes); `PlatformTest`: restart with pending work |

## Bidding

| Requirement | Covered by |
|---|---|
| BID-001/003/004 eligibility, starting price, increment | `BiddingTest`: the first bid must meet…; `BidRulesTest` |
| BID-002 sellers cannot bid | `BiddingTest`: sellers, unverified, suspended, early and late bids… |
| BID-005 immutable bids | No update or delete endpoint exists; bids are insert-only |
| BID-006/007 one transaction, serialized under concurrency | `BiddingTest`: concurrent bids are serialized… (row lock in `BiddingService.placeBid`) |
| BID-008 strictly increasing sequence | `BiddingTest`: concurrent bids… (gap-free sequences) |
| BID-009 winner is final highest bid | `BiddingTest`: the winner is the final highest bidder |
| BID-010 own history and masked public history | `BiddingTest`: public history masks bidders… |
| BID-011 stable rejection reasons | `BiddingTest`; `BidRulesTest` |
| BID-012 idempotent retries | `BiddingTest`: reusing an idempotency key… |
| Deadline rule `receivedAt < endAt` | `BiddingTest`: a bid received exactly at the deadline fails |

## Settlement

| Requirement | Covered by |
|---|---|
| SET-001 exactly one order per sold auction | `SettlementTest`: closing a sold auction creates exactly one order… (event redelivery) |
| SET-002 total = winning bid + shipping | `SettlementTest`; `OrderTest` |
| SET-003 PAYMENT_PENDING with 24 h deadline | `SettlementTest`: closing a sold auction… |
| SET-004 initiate or resume checkout | `SettlementTest`: only the winning buyer can check out, and checkout resumes… |
| SET-005 idempotent webhooks | `SettlementTest`: a success webhook marks the order paid once… |
| SET-006 only verified webhooks mark paid | `SettlementTest`: webhooks require a valid signature; checkout alone never pays |
| SET-007 failed attempts and expiry | `SettlementTest`: failed attempts allow another checkout; an unpaid order expires…; `OrderTest` |
| SET-008/009/010 shipment and delivery authorization | `SettlementTest`: only the seller ships…and only the buyer confirms delivery |
| SET-011 no secrets or raw payment data stored | Only provider references are stored; webhook secret comes from the environment |

## Architecture and platform

| Requirement | Covered by |
|---|---|
| §7.3 module boundaries, no cycles, clean domain | `ArchitectureTest` |
| §7.4 transactional outbox, retries, backoff, dead-lettering | `PlatformTest`: failing handlers are retried with backoff and dead-lettered |
| §8.3 correlation IDs through HTTP, audit and events | `PlatformTest`: correlation ids flow into audit records and outbox events; `HttpContractTest` |
| §6.5 HTTP status and error envelope | `HttpContractTest` |
| §10 documented public API | `OpenApiContractTest`: the spec documents every API route and nothing else |
| §9.5 end-to-end with restart | `PlatformTest`: registration through delivery survives a restart with pending work |
