# M3 Product Catalog — acceptance test traceability

**Spec files:** `features/product-catalog/src/test/resources/acceptance/*.feature`
**Runner:** `CatalogAcceptanceSuite` (`./gradlew :features:product-catalog:test --tests '*CatalogAcceptanceSuite*'`)
**Requirements:** `requirement.md` §5.2 (CAT-001..CAT-010), §6.2, §6.5, §9.2; USR-004/008/010 as seen by the catalog
**Plan and decisions:** `docs/M3_CATALOG_PLAN.md`, FLO epic [AFA-3](https://flo.odt.co.th/products/102/items/3)
**Created:** 2026-11-28 ([AFA-90](https://flo.odt.co.th/products/102/items/90))

95 scenarios (145 with outline examples expanded). All start tagged `@wip`; each item removes the
tag from its scenarios as the first step of implementing them.

## Requirement → scenarios

| Requirement | Feature file | Key scenarios | API item | UI item |
|---|---|---|---|---|
| CAT-001 eligible seller drafts one item | listing-drafts, seller-eligibility | create draft; not seller-enabled → 403; enabling in onboarding unlocks creation | AFA-33, AFA-32 | AFA-19 |
| CAT-002 listing content | listing-drafts, publication, listing-images | partial draft allowed; publish names each missing field; images upload/confirm/order | AFA-29, AFA-33, AFA-37 | AFA-19 |
| CAT-003 schedule within configured limits | publication | end before start; under 1 hour; over 14 days; start over 30 days ahead; start in the past | AFA-29, AFA-34 | AFA-19 |
| CAT-004 prices | publication | starting price 0 or negative; negative shipping; free shipping replaces shipping price | AFA-29, AFA-34 | AFA-19 |
| CAT-005 edit in DRAFT/SCHEDULED, economics locked when ACTIVE | listing-drafts | edit draft; edit scheduled economics; locked fields outline; non-economic edit while active; ended not editable | AFA-29, AFA-33 | AFA-19 |
| CAT-006 publish without review → SCHEDULED or ACTIVE | publication | future start → SCHEDULED; arrived start → ACTIVE; publish twice → 409 | AFA-34 | AFA-19 |
| CAT-007 public browse and search | browse-and-search, listing-detail | anonymous access; public states only; each filter; detail per state; drafts and cancelled → 404 | AFA-31, AFA-35 | AFA-17, AFA-18 |
| CAT-008 stable pagination, documented sorts | browse-and-search | sort orders; default ENDING_SOON; page 20 / max 50 / 51 → 400; stable under inserts; ties | AFA-31, AFA-35 | AFA-17 |
| CAT-009 cancel before first bid, final | cancellation | cancel DRAFT/SCHEDULED/ACTIVE without bids; with a bid → 409 (`@cross-feature`); final | AFA-34 | AFA-19 |
| CAT-010 close to SOLD/UNSOLD | scheduler | no bids → UNSOLD; bids → SOLD (`@cross-feature`) | AFA-36 | — |
| §5.2 jobs retry-safe and restart-tolerant | scheduler | run twice; two instances; downtime catch-up; bounded batches | AFA-36, AFA-91 | — |
| §6.2 mutations return the resource version | listing-drafts, publication | version on create, update and publish; stale version → 409 | AFA-33, AFA-34 | — |
| §6.5 status codes, error envelope, bounded pages, idempotency | all `@api` files | 400/401/403/404/409/422 cases; `PAGE_SIZE_TOO_LARGE`; Idempotency-Key required, replay, conflicting reuse | AFA-33, AFA-35 | — |
| USR-010 suspension blocks selling | seller-eligibility | suspended seller blocked on every write; public listings stay visible; reactivation | AFA-32 | — |
| Event delivery (decision 2026-11-27) | seller-eligibility, scheduler | delivery within seconds; replay idempotent; failing handler isolated; two pollers | AFA-91 | — |

## §9.2 acceptance bullets → scenarios

| §9.2 bullet | Scenario(s) |
|---|---|
| Invalid prices, missing required fields and invalid schedules prevent publication | publication: "Publication rules reject an invalid draft and name the problem" (16 examples) |
| A valid listing follows DRAFT → SCHEDULED → ACTIVE | publication: "A valid listing follows DRAFT, SCHEDULED, ACTIVE" |
| A listing with no bids can be cancelled; one with an accepted bid cannot | cancellation: "A listing without bids can be cancelled"; "A listing with an accepted bid cannot be cancelled" (`@cross-feature`) |
| Closing produces SOLD with bids and UNSOLD without | scheduler: "Closing without bids produces UNSOLD"; "Closing with bids produces SOLD" (`@cross-feature`) |
| Activation and closing jobs can run more than once without duplicate transitions | scheduler: "Running a job twice transitions once"; "Two app instances running the same job transition once" |

## Error codes the scenarios assume

New for the catalog, to be implemented as stable codes: `IDEMPOTENCY_KEY_REQUIRED`,
`IDEMPOTENCY_KEY_REUSED`, `LISTING_FIELD_LOCKED`, `LISTING_NOT_EDITABLE`, `LISTING_NOT_CANCELLABLE`,
`LISTING_ALREADY_PUBLISHED`, `LISTING_HAS_BIDS`, `PUBLICATION_RULES_FAILED` (with per-field codes
`REQUIRED`, `INVALID`, `MUST_BE_POSITIVE`, `MUST_NOT_BE_NEGATIVE`, `MUST_BE_AFTER_START`,
`DURATION_TOO_SHORT`, `DURATION_TOO_LONG`, `START_TOO_FAR_AHEAD`, `START_IN_PAST`),
`SELLER_NOT_ENABLED`, `PAGE_SIZE_TOO_LARGE`, `INVALID_CURSOR`, `IMAGE_TYPE_NOT_ALLOWED`,
`IMAGE_TOO_LARGE`, `TOO_MANY_IMAGES`, `IMAGE_NOT_UPLOADED`, `IMAGE_MISMATCH`.

Reused from the kernel and onboarding: `VALIDATION_FAILED`, `VERSION_CONFLICT`, `FORBIDDEN`,
`NOT_FOUND`, `RATE_LIMITED`, `ACCOUNT_SUSPENDED`.

## Gaps the scenarios expose

1. **Suspension lag (`@gap`).** USR-010 and §6.1 require every authenticated endpoint to check the
   application account state, but the catalog may not query onboarding. With the projection, a
   suspension takes effect in the catalog only after the event arrives (seconds). Options: accept
   that lag and document it, or add a kernel-level account-state check shared by all features.
   Decide on [AFA-32](https://flo.odt.co.th/products/102/items/32).
2. **Images on ACTIVE listings.** CAT-005 locks only economic fields once ACTIVE, and images are
   not economic. The scenarios allow image changes while DRAFT or SCHEDULED and refuse them once
   ended, but leave ACTIVE unspecified. Decide on [AFA-37](https://flo.odt.co.th/products/102/items/37).
3. **Search semantics for several words.** The scenarios only fix that a phrase matches its
   listing ("desk lamp"); whether several words mean AND or OR is open
   ([AFA-31](https://flo.odt.co.th/products/102/items/31)).
4. **Anonymous rate limit and cache lifetime.** The scenarios assume 120 searches per minute per
   address and public caching of at most 30 seconds; neither number is in the requirements.
5. **Currency.** Listings default to USD, the web app's `NEXT_PUBLIC_CURRENCY`; multi-currency is
   not in M3.
6. **Images in public responses.** Whether visitors get CDN URLs or short-lived signed URLs is
   still open on [AFA-92](https://flo.odt.co.th/products/102/items/92); the scenarios only require
   a display URL.
