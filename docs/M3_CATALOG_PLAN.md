# M3: Catalog API & UI — Implementation Plan

**Milestone:** https://github.com/ruufimon/auction-kotlin/milestone/3
**Created:** 2026-11-07
**Requirements:** `requirement.md` §5.2 (CAT-001..CAT-010), §6.2, §6.5, §9.2
**Preceded by:** M2 User-Onboarding (see milestone 2); **followed by:** M4 Bidding

## Exit criteria

- A seller-enabled user can create, edit, publish and cancel a listing through the API.
- Visitors can read and search public listings with bounded pagination.
- Due listings activate and close on schedule without duplicate transitions.
- M3-UI-1..3 (#33, #34, #35) run against these endpoints.

## Starting point (from the M1 branch, PR #26)

| Area | What exists | Gap for M3 |
|---|---|---|
| `listings` table | states `draft/scheduled/active/closed/cancelled`, prices in minor units, timing columns | needs `sold`/`unsold`, `condition`, shipping price / free shipping, origin, `currency`, `bid_count`, `version` (#47) |
| `listing_images` | url + display order | sufficient |
| `CatalogEvents.kt` | `AuctionSoldV1`, `AuctionUnsoldV1`, `ListingCancelledV1` | add `ListingPublishedV1`, `AuctionActivatedV1` (#46) |
| Prior code on `feat/api-v1` | `Listing.kt`, `ListingRules`, `ListingSearch`, `ListingRulesTest` | portable, but strip its direct imports of bidding and onboarding (AFA rule 2) |

## Endpoints (8)

| Method | Path | Issue |
|---|---|---|
| POST | `/api/v1/listings` | #50 |
| PUT | `/api/v1/listings/{listingId}` | #50 |
| GET | `/api/v1/listings/mine` | #50 |
| POST | `/api/v1/listings/{listingId}/publish` | #51 |
| POST | `/api/v1/listings/{listingId}/cancel` | #51 |
| GET | `/api/v1/listings/{listingId}` | #52 |
| GET | `/api/v1/listings/search` | #52 |
| POST | `/api/v1/listings/{listingId}/images` (only if upload is chosen) | #54 |

`GET /api/v1/listings/{listingId}/bids` is bid history and belongs to M4 Bidding.

## Issues

| # | Title | Depends on |
|---|---|---|
| #46 | M3-1 Listing domain aggregate, state machine & publication rules | M1 merged |
| #47 | M3-2 Catalog schema catch-up migration & categories | #46 |
| #48 | M3-3 ListingRepository with optimistic concurrency & bounded search | #46, #47 |
| #49 | M3-4 Seller eligibility projection fed by onboarding events | #24, event delivery decision |
| #50 | M3-5 Seller write endpoints (create, update, mine) | #48, #49, M2 auth plugin |
| #51 | M3-6 Publish & cancel endpoints | #50, outbox writer |
| #52 | M3-7 Public detail & search | #48 |
| #53 | M3-8 Scheduler jobs: activate, close to SOLD/UNSOLD | #51, #47 |
| #54 | M3-9 Image handling decision & endpoint | decision, #50 |
| #33 | M3-UI-1 Marketplace discovery | #29, #52 |
| #34 | M3-UI-2 Listing detail page | #33, #52, #46 |
| #35 | M3-UI-3 Seller listing create/edit/publish UI | #32, #50, #51, #54 |

## Sequencing

| PR | Issues | Notes |
|---|---|---|
| 1 | #46, #47 | domain + migration; unblocks everything |
| 2 | #48, #50 | repository + seller write endpoints |
| 3 | #51, #53 | publish/cancel + scheduler jobs |
| 4 | #52 | public read/search; parallel with PR 3 |
| 5 | #49 | eligibility projection; needs event delivery |
| UI | #33 → #34 → #35 | #33 starts once PR 4 is reviewable |

Target: weeks 2–3 per `MILESTONES.md`.

## Decisions required before starting

1. **Event delivery.** #49 and suspension enforcement need delivered events; the outbox poller is scheduled for M6. Recommendation: pull a minimal in-process poller into M3 and share its job runner with #53.
2. **Image storage.** URL references only, or uploads to DigitalOcean Spaces (#54).
3. **Categories.** Configured list or database table (#47).
4. **Limits.** Minimum/maximum auction duration, maximum start lead time (CAT-003), and search page-size cap (req 6.5).
