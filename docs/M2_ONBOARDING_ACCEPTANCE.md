# M2 User-Onboarding — acceptance test traceability

**Spec files:** `features/user-onboarding/src/test/resources/acceptance/*.feature`
**Requirements:** `requirement.md` §5.1 (USR-001..014), §6.5, §8.2, §9.1, as amended by PR #60 (Keycloak with Google sign-in)
**Screens:** onboarding design canvas (linked from issues #30, #31, #32)
**Created:** 2026-11-07

## Requirement → scenarios

| Requirement | Feature file | Key scenarios | API issue | UI issue | Screen |
|---|---|---|---|---|---|
| USR-001 Google or email/password at provider; linking | registration | bootstrap by identity type; Google links to existing account; provider duplicate email | #19, #21 | #30 | 1 |
| USR-002 provider password policy, no app passwords | registration | provider rejects weak passwords; app has no password column | #19 | #30 | 1 |
| USR-003 provider verification link | email-verification | link once, expired, resend (provider) | #19 | #30 | 2, 3 |
| USR-004 verified before bid/publish, enforced by app state | email-verification, sign-in | email_verified=false token refused; verification alone does not activate | #20, #21 | #30 | 2, 3 |
| USR-005 OIDC code+PKCE, app validates tokens | sign-in | wrong key / issuer / audience / expired → 401; valid token does not override app state | #19, #20 | #31 | 4 |
| USR-006 rotation and reuse detection at provider | token-refresh-and-signout | rotates; reuse ends session; app holds no refresh tokens | #19 | #31 | 4 |
| USR-013 app account keyed by subject, created on first request | registration, email-verification | bootstrap states; concurrent bootstrap; transition on email_verified | #21 | #30 | 1–3 |
| USR-014 realm as code | all provider scenarios | every provider scenario runs against the versioned realm | #19 | — | — |
| USR-007 view/update profile | profile | own profile read/update; other user → 403; version conflict | #23 | #32 | 6 |
| USR-008 seller eligibility by configured fields | seller-enablement | missing field → 422 naming it; config-driven field set | #24 | #32 | 7 |
| USR-009 one account, buyer and seller | registration, seller-enablement | no role choice; seller still bids; same user id | #19, #24 | #32 | 1, 7 |
| USR-010 suspension on both sides, records kept | suspension | app rejects valid token; provider disabled and sessions ended; outbox retry; reactivation rules | #22 | #31, #32 | 4, 6 |
| USR-011 age/income/wealth after verification, minor units | credit-eligibility | only after verification; invalid inputs; minor units | #23, #17 | #32 | 5 |
| USR-012 versioned policy, auditable outcome, encrypted inputs | credit-eligibility | pass activates; fail keeps pending with reason; policy version pinned; encrypted; never logged or returned | #23, #18 | #32 | 5, 6 |
| §8.2 abuse controls | sign-in, abuse-controls | provider brute force lockout; app bootstrap 429 | #19, #21 | #31 | 4 |

## §9.1 acceptance bullets → scenarios

| §9.1 bullet | Scenario(s) |
|---|---|
| First authenticated request creates PENDING_VERIFICATION (password) or PENDING_CREDIT_ELIGIBILITY (Google verified) | registration: first two scenarios |
| Google sign-in with matching verified email links, no second account | registration: Google identity links |
| Token with email_verified=false refused for bidding/publishing | email-verification: valid token whose claim is false |
| Provider: link once, expiry, rotation, reuse, password policy, brute force | email-verification, token-refresh-and-signout, sign-in (provider scenarios) |
| Suspended: provider refuses sign-in/refresh; app rejects pre-suspension token | suspension |
| Wrong key / issuer / audience rejected | sign-in: tokens not from this realm |
| Verification moves to credit-eligibility pending; pass activates; fail prevents bidding and publication | email-verification: advances state; credit-eligibility: passing / failing / failed cannot bid or publish |
| Inputs evaluated with the configured policy version and never in logs | credit-eligibility: policy version recorded; never appear in logs |

| Completing required seller fields enables publication without a second account | seller-enablement: complete profile enables; unlocks publication; end to end |

## Gaps the scenarios expose

1. **Assessment form UI** has no dedicated issue; it is folded into #32. Consider splitting.
2. **Operator suspend / reactivate** endpoints now live in #22; their own authentication (operator role) is still undefined.
3. **SMTP provider** for Keycloak's verification mail and **Google OAuth client credentials** are user actions before #19 can be tested end to end.
4. **Re-assessment policy** (retry window after a failed assessment) is undefined in the requirements; `credit-eligibility.feature` assumes the policy declares one.
5. **Error codes** (`TOKEN_MISSING`, `TOKEN_INVALID`, `TOKEN_EXPIRED`, `TOKEN_WRONG_AUDIENCE`, `ACCOUNT_NOT_BOOTSTRAPPED`, `ACCOUNT_*` state codes, `FORBIDDEN`, `VALIDATION_FAILED`, `CREDIT_ELIGIBILITY_NOT_MET`, `ASSESSMENT_RETRY_NOT_YET_ALLOWED`, `SELLER_PROFILE_INCOMPLETE`, `SELLER_ALREADY_ENABLED`, `VERSION_CONFLICT`, `RATE_LIMITED`, `REACTIVATION_PRECONDITIONS_NOT_MET`) are now the implemented contract (`kernel` `ApiError`, `onboarding` `ErrorMapping.kt`).

## Status (2026-11-07)

Wired and running: `./gradlew :features:user-onboarding:test` executes every scenario that does not
need the real realm, another milestone's endpoints or a browser. 59 scenarios pass against the
application; `@provider` (19), `@cross-feature` (12), `@ui` (8) and `@wip` (1: SigNoz visibility of
abuse events) are excluded until #19, M3–M5, #42 and the logs pipeline exist. Harness details:
`features/user-onboarding/src/test/resources/acceptance/README.md`.
