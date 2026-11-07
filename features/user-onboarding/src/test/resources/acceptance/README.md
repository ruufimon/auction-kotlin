# User-onboarding acceptance tests

Executable specification for milestone M2 (User-Onboarding Complete), written as
Gherkin so each scenario traces to a requirement in `requirement.md` §5.1 / §9.1
and to a GitHub issue. Identity is provided by Keycloak (requirement PR #60): scenarios
marked "(provider)" exercise the versioned realm through a Testcontainers Keycloak.

| File | Requirements | Issues |
|---|---|---|
| `registration.feature` | USR-001, USR-002, USR-003, USR-009, USR-013 | #19, #21, #30 |
| `email-verification.feature` | USR-003, USR-004, USR-013, §5.1 states | #19, #21, #30 |
| `sign-in.feature` | USR-005 (token validation, account-state authorization) | #19, #20, #31 |
| `token-refresh-and-signout.feature` | USR-005, USR-006 (provider behaviour) | #19, #31 |
| `credit-eligibility.feature` | USR-011, USR-012 | #17, #18, #23, #32 |
| `profile.feature` | USR-007 | #23, #32 |
| `seller-enablement.feature` | USR-008, USR-009 | #24, #32 |
| `suspension.feature` | USR-010 (both systems) | #22 |
| `abuse-controls.feature` | §8.2 (provider brute force + app bootstrap limit) | #19, #21 |

## Conventions

- Tags: `@USR-nnn` = requirement, `@issue-nn` = GitHub issue, `@api` = HTTP-level,
  `@ui` = needs the web app (M7-UI-1 browser E2E), `@gap` = behaviour the
  requirements demand but no issue implements yet.
- Money is always integer minor units (`6800000` = 68,000.00 in a 2-decimal currency).
- Error responses follow §6.5: `{correlationId, code, message, details[]}`. Scenarios
  assert on the stable `code`, never the message text.
- "the configured policy" means the deployment-configured scoring policy of USR-012;
  tests pin a known test policy (see `Background` in `credit-eligibility.feature`).

## Running them

```bash
./gradlew :features:user-onboarding:test --tests '*AcceptanceSuite*'
# one scenario, with step output
./gradlew :features:user-onboarding:test --tests '*AcceptanceSuite*' \
    -Dcucumber.filter.name="Concurrent edits are detected" -Dcucumber.plugin=pretty
```

Harness (`src/test/kotlin/com/gavel/onboarding/acceptance/`):

- `AcceptanceSuite` runs Cucumber on the JUnit platform with the tag filter
  `not @ui and not @provider and not @cross-feature and not @wip`.
- `World` holds per-scenario state: a `TestIdentityProvider` (kernel test fixture) that mints
  real RS256 tokens the application validates through its normal validator, a `TestApplication`
  built from `TestApp.kt` (kernel plugins + the onboarding feature), a mutable clock, and the
  Testcontainers PostgreSQL from `TestDatabase` (truncated before each scenario).
- Steps drive only the HTTP API (`/api/v1/me`, `/api/v1/profile/{userId}`, `/internal/accounts/...`).
  Fixtures such as "mina is ACTIVE" are built by calling those endpoints, never by writing rows.
- "Given a Keycloak realm ..." steps are no-ops in this profile: realm behaviour is asserted by
  `@provider` scenarios, which run against a Testcontainers Keycloak once #19 lands.

Tags: `@provider` (needs the real realm), `@cross-feature` (needs bidding/catalog/settlement
endpoints from M3–M5), `@ui` (browser suite #42), `@wip` (specified, not implemented: remove the
tag as the first step of implementing it).

## Testing with and without Keycloak

Keycloak is bypassed wherever the subject under test is the application, and required only
where the subject is the realm configuration itself.

| Layer | Keycloak? | How |
|---|---|---|
| Domain unit tests | No | The account aggregate has no identity-provider dependency. |
| Ktor route tests (bootstrap, authorization by state, profile, assessment, seller, token-rejection cases) | No | The validator trusts a JWKS served by the test itself; tests mint tokens with the claims each scenario needs (`sub`, `email`, `email_verified`, `aud`, `exp`). Wrong-key, wrong-issuer, wrong-audience and expired cases use a second test key or altered claims. |
| Provider scenarios (rotation, reuse, brute force, verification links, password policy, Google linking) | **Yes** | Testcontainers Keycloak importing `deploy/keycloak/realm-gavel.json`; a mock OIDC server stands in for Google in the test realm. Tagged `@provider`; run on realm changes and nightly, not on every commit. |
| Local development | Yes (compose) | Real Keycloak in `docker-compose.dev.yml` with the versioned realm and seeded users. Optional non-production-only extra trusted JWKS file for fast loops; the app refuses to start with it set in production. |
| Browser E2E (#42) | Yes | Log in once through the themed Keycloak page with a seeded password user; reuse the browser storage state. Never a real Google account. |

Rules: no bypass flag in production code paths (the only seam is which signing keys are trusted), and
every minted test token still passes through the real validator.
