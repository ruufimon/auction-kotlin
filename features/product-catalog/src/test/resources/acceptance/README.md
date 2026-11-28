# Product-catalog acceptance tests

Executable specification for milestone M3 (Catalog API & UI), written before the code
([AFA-90](https://flo.odt.co.th/products/102/items/90)). Each scenario traces to a requirement
in `requirement.md` §5.2 / §9.2 and to a FLO item; the full table is in
`docs/M3_CATALOG_ACCEPTANCE.md`.

| File | Requirements | FLO items |
|---|---|---|
| `listing-drafts.feature` | CAT-001, CAT-002, CAT-005, §6.2, §6.5 | AFA-33 |
| `publication.feature` | CAT-002, CAT-003, CAT-004, CAT-006, §9.2 | AFA-29, AFA-34 |
| `cancellation.feature` | CAT-009, §9.2 | AFA-34 |
| `browse-and-search.feature` | CAT-007, CAT-008, §6.5 | AFA-31, AFA-35 |
| `listing-detail.feature` | CAT-007 | AFA-35 |
| `scheduler.feature` | CAT-010, §5.2 listing states, §9.2 | AFA-36, AFA-91 |
| `seller-eligibility.feature` | USR-004, USR-008, USR-010 (as seen by the catalog) | AFA-32, AFA-91 |
| `listing-images.feature` | CAT-002 (images) | AFA-37, AFA-92 |
| `catalog-web.feature` | browser checks | AFA-17, AFA-18, AFA-19 |

## Conventions

Same as the onboarding suite (`features/user-onboarding/src/test/resources/acceptance/README.md`):

- Tags: `@CAT-nnn` / `@USR-nnn` = requirement, `@AFA-nn` = FLO item, `@api` = HTTP level.
- Money is integer minor units; times in assertions are UTC.
- Errors follow §6.5 (`{correlationId, code, message, details[]}`); scenarios assert the stable
  `code`, never the message.
- Steps drive the HTTP API only. Fixtures such as "mina has an ACTIVE listing" are built through
  the API plus the test clock and the jobs, never by writing rows directly.
- Tests use MinIO for images and a mutable test clock for the scheduler; never DigitalOcean.

Excluded from the Cucumber run:

| Tag | Meaning | Removed when |
|---|---|---|
| `@wip` | Specified, not implemented | The implementing item lands (remove the tag first, then make it pass) |
| `@ui` | Browser behaviour | Never: Playwright in `web/e2e` covers it |
| `@cross-feature` | Needs bidding (M4) or settlement (M5) | Those milestones land |
| `@gap` | Needs a decision first | The decision is recorded on the FLO item |

## Running them

```bash
./gradlew :features:product-catalog:test --tests '*CatalogAcceptanceSuite*'
# one scenario, with step output
./gradlew :features:product-catalog:test --tests '*CatalogAcceptanceSuite*' \
    -Dcucumber.filter.name="A due SCHEDULED listing becomes ACTIVE" -Dcucumber.plugin=pretty
```

The harness (test app, world, database, test identity provider) follows the onboarding suite's
`TestApp`/`World` pattern and is added by the first item that removes a `@wip` tag.
