# M2 User-Onboarding — acceptance test traceability

**Spec files:** `features/user-onboarding/src/test/resources/acceptance/*.feature`
**Requirements:** `requirement.md` §5.1 (USR-001..012), §6.5, §8.2, §9.1
**Screens:** onboarding design canvas (linked from issues #30, #31, #32)
**Created:** 2026-11-07

## Requirement → scenarios

| Requirement | Feature file | Key scenarios | API issue | UI issue | Screen |
|---|---|---|---|---|---|
| USR-001 unique normalized email | registration | successful registration; normalized duplicate → 409 | #19 | #30 | 1 |
| USR-002 password policy, memory-hard hash | registration | weak passwords; stored only as memory-hard hash | #19 | #30 | 1 |
| USR-003 single-use expiring token | registration, email-verification | token sent; reuse → 422; expiry → 404; resend invalidates old | #20 | #30 | 2, 3 |
| USR-004 verify before bid/publish | registration, email-verification | pending cannot bid; verification alone does not activate | #20 | #30 | 2, 3 |
| USR-005 JWT + rotating refresh | sign-in, token-refresh-and-signout | tokens issued; refresh rotates; not-active states → 403 | #21, #22 | #31 | 4 |
| USR-006 refresh hashed, expiry, invalidation | token-refresh-and-signout, suspension | reuse burns family; expired; sign-out; suspension revokes | #22, #23 | #31 | 4, 6 |
| USR-007 view/update profile | profile | own profile read/update; other user → 403; version conflict | #23 | #32 | 6 |
| USR-008 seller eligibility by configured fields | seller-enablement | missing field → 422 naming it; config-driven field set | #24 | #32 | 7 |
| USR-009 one account, buyer and seller | registration, seller-enablement | no role choice; seller still bids; same user id | #19, #24 | #32 | 1, 7 |
| USR-010 suspension immediate, records kept | suspension | blocks refresh/sign-in/bid/publish/fulfilment; records readable; reactivation rules | cross-cutting | #31, #32 | 4, 6 |
| USR-011 age/income/wealth after verification, minor units | credit-eligibility | only after verification; invalid inputs; minor units | #23, #17 | #32 | 5 |
| USR-012 versioned policy, auditable outcome, encrypted inputs | credit-eligibility | pass activates; fail keeps pending with reason; policy version pinned; encrypted; never logged or returned | #23, #18 | #32 | 5, 6 |
| §8.2 rate limiting on auth endpoints | sign-in, abuse-controls | 429 with Retry-After | **no issue** | #31 | 4 |

## §9.1 acceptance bullets → scenarios

| §9.1 bullet | Scenario(s) |
|---|---|
| Registration creates a pending account and does not allow bidding before verification | registration: successful registration; a pending account cannot bid |
| A verification token works once and fails after use or expiry | email-verification: valid token; cannot be used twice; expired token |
| Verification moves to credit-eligibility pending; pass activates; fail prevents bidding and publication | email-verification: advances state; credit-eligibility: passing / failing / failed cannot bid or publish |
| Inputs evaluated with the configured policy version and never in logs | credit-eligibility: policy version recorded; never appear in logs |
| Refresh rotates; reuse invalidates the family | token-refresh-and-signout: rotates; reuse invalidates family |
| Suspended account cannot refresh, bid, publish, or update fulfilment | suspension: four blocking scenarios |
| Completing required seller fields enables publication without a second account | seller-enablement: complete profile enables; unlocks publication; end to end |

## Gaps the scenarios expose

1. **Rate limiting** (§8.2) has no M2 issue. `abuse-controls.feature` is tagged `@gap`.
2. **Assessment form UI** has no dedicated issue; it is folded into #32. Consider splitting.
3. **Operator suspend / reactivate** endpoints are assumed by USR-010 but no issue defines them.
4. **Re-assessment policy** (retry window after a failed assessment) is undefined in the requirements; `credit-eligibility.feature` assumes the policy declares one.
5. **Error codes** used here (`EMAIL_ALREADY_REGISTERED`, `VERIFICATION_TOKEN_*`, `ACCOUNT_*`, `REFRESH_TOKEN_*`, `CREDIT_ELIGIBILITY_NOT_MET`, `SELLER_PROFILE_INCOMPLETE`, `VERSION_CONFLICT`, `RATE_LIMITED`) are proposals; they become the contract once M2-1 (#17) lands.

## How these become executable

See `features/user-onboarding/src/test/resources/acceptance/README.md`. In short:
Cucumber-JVM on the JUnit platform, step definitions in the onboarding module driving
Ktor `testApplication` over the Testcontainers PostgreSQL fixture from #16, mail
captured through the `EmailService` stub from #20, `@ui` scenarios deferred to the
browser E2E suite (#42).
