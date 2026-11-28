# M3: Catalog API & UI — Implementation Plan

**Epic:** [AFA-3](https://flo.odt.co.th/products/102/items/3) in FLO (GitHub Issues are retired)
**Created:** 2026-11-07
**Requirements:** `requirement.md` §5.2 (CAT-001..CAT-010), §6.2, §6.5, §9.2
**Preceded by:** M2 User-Onboarding (epic AFA-2); **followed by:** M4 Bidding

## Exit criteria

- A seller-enabled user can create, edit, publish and cancel a listing through the API.
- Visitors can read and search public listings with bounded pagination.
- Due listings activate and close on schedule without duplicate transitions.
- M3-UI-1..3 (AFA-17, AFA-18, AFA-19) run against these endpoints.

## Starting point (from the M1 branch, PR #26)

| Area | What exists | Gap for M3 |
|---|---|---|
| `listings` table | states `draft/scheduled/active/closed/cancelled`, prices in minor units, timing columns | needs `sold`/`unsold`, `condition`, shipping price / free shipping, origin, `currency`, `bid_count`, `version` (AFA-30) |
| `listing_images` | url + display order | sufficient |
| `CatalogEvents.kt` | `AuctionSoldV1`, `AuctionUnsoldV1`, `ListingCancelledV1` | add `ListingPublishedV1`, `AuctionActivatedV1` (AFA-29) |
| Prior code on `feat/api-v1` | `Listing.kt`, `ListingRules`, `ListingSearch`, `ListingRulesTest` | portable, but strip its direct imports of bidding and onboarding (AFA rule 2) |

## Endpoints (8)

| Method | Path | Issue |
|---|---|---|
| POST | `/api/v1/listings` | AFA-33 |
| PUT | `/api/v1/listings/{listingId}` | AFA-33 |
| GET | `/api/v1/listings/mine` | AFA-33 |
| POST | `/api/v1/listings/{listingId}/publish` | AFA-34 |
| POST | `/api/v1/listings/{listingId}/cancel` | AFA-34 |
| GET | `/api/v1/listings/{listingId}` | AFA-35 |
| GET | `/api/v1/listings/search` | AFA-35 |
| POST | `/api/v1/listings/{listingId}/images` (only if upload is chosen) | AFA-37 |

`GET /api/v1/listings/{listingId}/bids` is bid history and belongs to M4 Bidding.

## Items

| Item | Title | Depends on |
|---|---|---|
| AFA-29 | M3-1 Listing domain aggregate, state machine & publication rules | M1 merged |
| AFA-30 | M3-2 Catalog schema catch-up migration & categories | AFA-29 |
| AFA-31 | M3-3 ListingRepository with optimistic concurrency & bounded search | AFA-29, AFA-30 |
| AFA-32 | M3-4 Seller eligibility projection fed by onboarding events | AFA-91 (poller); AFA-89 publishes SellerEnabledV1 |
| AFA-33 | M3-5 Seller write endpoints (create, update, mine) | AFA-31, AFA-32, M2 auth plugin |
| AFA-34 | M3-6 Publish & cancel endpoints | AFA-33, outbox writer |
| AFA-35 | M3-7 Public detail & search | AFA-31 |
| AFA-36 | M3-8 Scheduler jobs: activate, close to SOLD/UNSOLD | AFA-91, AFA-34, AFA-30 |
| AFA-37 | M3-9 Image upload endpoint (Spaces) | AFA-33, AFA-92 |
| AFA-17 | M3-UI-1 Marketplace discovery | AFA-13, AFA-35 |
| AFA-18 | M3-UI-2 Listing detail page | AFA-17, AFA-35, AFA-29 |
| AFA-19 | M3-UI-3 Seller listing create/edit/publish UI | AFA-16, AFA-33, AFA-34, AFA-37 |

## Sequencing

| PR | Issues | Notes |
|---|---|---|
| 1 | AFA-29, AFA-30 | domain + migration; unblocks everything |
| 2 | AFA-31, AFA-33 | repository + seller write endpoints |
| 3 | AFA-34, AFA-36 | publish/cancel + scheduler jobs |
| 4 | AFA-35 | public read/search; parallel with PR 3 |
| 5 | AFA-32 | eligibility projection; needs event delivery |
| UI | AFA-17 → AFA-18 → AFA-19 | AFA-17 starts once PR 4 is reviewable |

Target: weeks 2–3 per `MILESTONES.md`.

## Decisions (2026-11-27)

1. **Event delivery:** a minimal in-process outbox poller and job runner in M3 ([AFA-91](https://flo.odt.co.th/products/102/items/91)); M6 adds retries with backoff, dead letters and monitoring.
2. **Image storage:** direct upload to DigitalOcean Spaces with pre-signed URLs ([AFA-92](https://flo.odt.co.th/products/102/items/92), AFA-37).
3. **Categories:** a database table seeded by migration (AFA-30).
4. **Limits:** auction length 1 hour to 14 days, start at most 30 days ahead, search page 20 by default and 50 at most; all configurable.

## Added items (2026-11-28)

| Item | What |
|---|---|
| [AFA-90](https://flo.odt.co.th/products/102/items/90) M3-0 | Acceptance scenarios and this plan update (`docs/M3_CATALOG_ACCEPTANCE.md`) |
| [AFA-91](https://flo.odt.co.th/products/102/items/91) M3-10 | Outbox poller and job runner |
| [AFA-92](https://flo.odt.co.th/products/102/items/92) M3-11 | Spaces bucket and storage setup |
| [AFA-93](https://flo.odt.co.th/products/102/items/93) M3-12 | Production rollout and smoke test |

The order of work and every dependency are kept on the FLO items and the AFA-3 epic summary.
