# M2 User-Onboarding — SLO plan

**Scope:** the onboarding journey (register → verify email → sign in → credit-eligibility
assessment → profile → enable selling) plus token refresh, which every later request depends on.
**Identity:** per requirement PR #60, registration, verification, sign-in and refresh are served by a
self-hosted **Keycloak** (Google + email/password). Those steps are measured as a dependency; the
application's own SLIs start at token validation and account bootstrap.
**Requirements:** `requirement.md` §8.1 (liveness/readiness), §8.3 (observability), §8.4 (performance), §8.2 (abuse controls)
**Deployment context:** one App Platform instance (`apps-s-1vcpu-1gb`, region sgp), one managed PostgreSQL (`db-s-1vcpu-1gb`), no metrics stack yet (only Logback)
**Created:** 2026-11-07 · **Review:** after 30 days of production traffic, then quarterly

## 1. Principles

- SLOs are set for what a single instance behind App Platform can honestly promise. Raise them after load testing (§8.4) and a second instance, not before.
- Every SLI is measured server-side in the API, never from the browser, so it is independent of the web app's hosting.
- Labels never carry personal data: no emails, no user ids, no assessment inputs. Stable codes (`outcome`, `code`, `policy_version`) are fine.
- Two kinds of targets: **availability / latency** (error-budget managed) and **correctness invariants** (any violation is an incident, no budget).

## 2. SLIs and SLOs

Window: 30 days rolling. "Good" excludes 4xx responses from the denominator for latency, and counts 5xx and timeouts as bad for availability. 429 is neither bad nor good for availability (it is the abuse control working) but is tracked separately.

### 2.1 Availability

| SLI | SLO | Error budget / 30 d | Routes |
|---|---|---|---|
| Onboarding API availability: non-5xx responses ÷ all responses | **99.5 %** | 3 h 36 min | `/api/v1/me`, `/api/v1/profile/*` |
| Keycloak availability (dependency): OIDC discovery + token endpoint responding 2xx/4xx | **99.5 %** | 3 h 36 min | probed externally; an outage here blocks every sign-in |
| Readiness: `/health/ready` returning 200 | **99.5 %** | 3 h 36 min | probe every 10 s (App Platform) |

Rationale: App Platform redeploys cause brief readiness gaps on a single instance; 99.9 % is not credible until `instance_count: 2`.

### 2.2 Latency (server-side, successful requests, p95 and p99)

| Step | Route | p95 | p99 | Why this number |
|---|---|---|---|---|
| Register / sign-in page (Keycloak, dependency) | provider login + token endpoint | 1 s | 2 s | Argon2 verify inside Keycloak plus Google round-trip for social login |
| Token refresh (Keycloak, dependency) | provider token endpoint | 300 ms | 800 ms | on the hot path of every session |
| Account bootstrap | `GET /api/v1/me` | 300 ms | 600 ms | JWKS cached; one row lookup or insert + outbox |
| Token validation overhead (app) | all authenticated routes | 5 ms | 20 ms | signature check against cached JWKS; a JWKS refetch must not sit on the request path |
| Read profile | `GET /profile/{id}` | 200 ms | 500 ms | single row, decrypt nothing |
| Update profile (no assessment) | `PUT /profile/{id}` | 300 ms | 600 ms | |
| Credit assessment | `PUT /profile/{id}` with inputs | 500 ms | 1 s | encrypt three fields + policy evaluation + two outbox inserts |
| Enable seller | `POST /profile/{id}/enable-seller` | 300 ms | 600 ms | |
| Suspend (operator) | `POST /internal/accounts/{id}/suspend` | 1 s | 2 s | includes Keycloak admin API call; app state flips first |

Each latency SLO is met when ≥ 99 % of 5-minute windows in the month satisfy the p95 target.

### 2.3 Asynchronous steps

| SLI | SLO | Source |
|---|---|---|
| Verification email dispatch (Keycloak → SMTP) | p95 ≤ **60 s** | Keycloak event stream (`SEND_VERIFY_EMAIL`, `SEND_VERIFY_EMAIL_ERROR`) |
| Verification → app state propagation: `email_verified` true at provider → account `PENDING_CREDIT_ELIGIBILITY` | on the user's next request, ≤ **1 request** lag | bootstrap transition counter |
| Suspension propagation: app suspended → Keycloak user disabled and sessions ended | p95 ≤ **30 s** | outbox lag for the admin-API call |
| Onboarding event delivery lag (all `user-onboarding.*` events) | p95 ≤ **30 s** | outbox lag gauge (§8.4 requires this to be alerted on) |
| Outbox backlog of unpublished onboarding events | < **100** for more than 10 min is an alert | outbox backlog gauge |

### 2.4 Correctness invariants (zero tolerance)

These come straight from §9.1 and USR-006/USR-012. Each is a counter that should stay at 0 in production; any increment pages.

| Invariant | Signal |
|---|---|
| No request with a token signed by a non-realm key, wrong issuer or wrong audience is ever authorized | `auth_token_validation_total{outcome="accepted", issuer!=realm}` = 0 |
| A valid token for a `SUSPENDED` account is never authorized for bid, publish or fulfilment | `onboarding_invariant_violations_total{kind="suspended_authorized"}` = 0 |
| One application account per provider subject and per verified email | `onboarding_invariant_violations_total{kind="duplicate_account"}` = 0 (unique index + bootstrap check) |
| Refresh-token reuse ends the session (Keycloak) | provider event `REFRESH_TOKEN_ERROR` with reuse reason is followed by `LOGOUT`; checked in the acceptance suite against the versioned realm, monitored via Keycloak events |
| Assessment raw inputs never logged | log-pipeline rule scanning onboarding log lines for the assessment field names with numeric values; count must be 0 |
| Account activated only with a passing assessment | `onboarding_state_transitions_total{to="active"}` ≤ `credit_assessment_total{outcome="eligible"}` |

### 2.5 Abuse-control signals (monitor, not SLO)

- Keycloak events `LOGIN_ERROR` ratio over 15 min > 60 % with ≥ 50 attempts → security alert (credential stuffing); brute-force lockouts per hour.
- `ratelimit_hits_total{endpoint}` for `/api/v1/me` (bootstrap) in the app.
- Account-linking events per day (unexpected spikes indicate abuse of the link-by-email flow).

### 2.6 Journey indicators (product health, no SLO yet)

Tracked on the dashboard to establish baselines before any target is set:
registration → verified within 24 h; verified → assessment submitted within 7 d; assessment pass rate per `policy_version`; active → seller-enabled within 30 d.

## 3. Alerting

Multi-window burn-rate alerts on the two availability SLOs and on each latency SLO:

| Severity | Burn rate | Long window | Short window | Meaning |
|---|---|---|---|---|
| Page | 14.4× | 1 h | 5 min | 2 % of the monthly budget gone in an hour |
| Page | 6× | 6 h | 30 min | 5 % of the budget gone in six hours |
| Ticket | 1× | 3 d | 6 h | budget on track to be exhausted |

Guards for low traffic: suppress latency and availability alerts when the long window holds fewer than 100 requests for that route. Correctness-invariant counters, outbox backlog and readiness alerts have no traffic guard.

Error-budget policy: when the 30-day budget for any onboarding availability or latency SLO is exhausted, feature work on that route pauses in favour of reliability work until the burn rate is back under 1× for 7 days.

## 4. Instrumentation plan

### 4.1 Platform (prerequisite, one issue)

- Add Micrometer via `ktor-server-metrics-micrometer` with a Prometheus registry at `/metrics` (not routed publicly; App Platform internal only or protected by a token).
- `http.server.requests` with route templates as `uri` (never raw paths with ids), `status`, `method`, `outcome`.
- Correlation id on every log line and propagated into outbox rows (§8.3), already a column (`correlation_id`).
- Structured JSON logging via Logback encoder with `service_version`, `module`, `correlation_id`.

### 4.2 Onboarding metrics (one issue, implemented alongside each M2 endpoint)

| Metric | Type | Labels |
|---|---|---|
| `onboarding_bootstrap_total` | counter | `outcome` = created_pending_verification \| created_pending_eligibility \| linked \| existing \| rate_limited |
| `auth_token_validation_total` | counter | `outcome` = accepted \| invalid \| expired \| wrong_audience \| wrong_issuer |
| `onboarding_state_transitions_total` | counter | `from`, `to` |
| `account_suspension_total` | counter | `outcome` = suspended \| reactivated \| provider_call_failed |
| (Keycloak) `LOGIN`, `LOGIN_ERROR`, `REFRESH_TOKEN`, `REFRESH_TOKEN_ERROR`, `SEND_VERIFY_EMAIL`, `VERIFY_EMAIL`, `IDENTITY_PROVIDER_LOGIN`, `IDENTITY_PROVIDER_FIRST_LOGIN` | provider events | shipped to SigNoz via Keycloak's metrics endpoint / event listener |
| `credit_assessment_total` | counter | `outcome` = eligible \| not_eligible \| invalid, `policy_version` |
| `credit_assessment_duration_seconds` | histogram | — |
| `seller_enable_total` | counter | `outcome` = enabled \| incomplete \| not_active \| already |
| `outbox_backlog` | gauge | `feature` = user-onboarding |
| `outbox_lag_seconds` | histogram | `feature` |
| `ratelimit_hits_total` | counter | `endpoint` |
| `onboarding_invariant_violations_total` | counter | `kind` |
| `jwks_refresh_total` | counter | `outcome` = ok \| failed (a failing refresh is an incident before tokens start being rejected) |

### 4.3 Metrics backend (decision needed)

App Platform's built-in monitoring covers CPU, memory and restarts only, and does not scrape Prometheus endpoints. Options:

1. **Grafana Cloud free tier**, remote-write or OTLP from the app. Cheapest, hosted dashboards and alerting. Recommended for now.
2. A **Prometheus + Grafana worker** on a Droplet. More control, more to run.
3. **DigitalOcean Managed OpenSearch** for logs only, with metrics derived from logs. Weakest for burn-rate alerts.

### 4.4 Validation before launch (§8.4)

A k6 scenario covering Keycloak sign-in (password grant only for the load test client), token refresh and `GET /api/v1/me` at the expected peak (assume 5 registrations/min and 50 sign-ins/min for launch, to be confirmed) run against a staging App Platform app and a staging Keycloak. It must show the Keycloak instance size (1 GB) holds the dependency targets and that JWKS caching keeps app-side validation overhead under its target.

## 5. Dashboard

One "Onboarding" dashboard with four rows: funnel counters and conversion; per-step availability and p95/p99 against SLO lines with remaining error budget; async health (email dispatch delay, outbox lag and backlog); abuse and invariants (rate-limit hits, failed-sign-in ratio, invariant counters).

## 6. Issues to file

| Proposed issue | Milestone | Depends on |
|---|---|---|
| SLO-1 Micrometer + Prometheus `/metrics`, JSON logs with correlation id | M2 | #26 (merged) |
| SLO-2 Onboarding business metrics and invariant counters | M2 | SLO-1, #20–#24 |
| SLO-2b Keycloak metrics/events into SigNoz (metrics endpoint + event listener), external probe of OIDC discovery | M2 | #19, SLO-3 |
| SLO-3 Metrics backend decision and remote write from App Platform | M2 | SLO-1 |
| SLO-4 Burn-rate alert rules and onboarding dashboard | M6 or M7 | SLO-2, SLO-3 |
| SLO-5 k6 load test for register / sign-in / refresh against staging | M7 | #19, #21, #22 |

## 7. Open questions

1. Expected launch traffic, to size the low-traffic alert guards and the load test.
2. Keycloak instance size and its password-hashing cost; they set the sign-in latency floor. Keycloak availability caps the product's effective availability, so its SLO must be at least the API's.
3. Which email provider, since its acceptance latency bounds the dispatch SLO.
4. Whether `/metrics` is exposed through App Platform internal routing or pushed, which decides between options 1 and 2 in §4.3.
