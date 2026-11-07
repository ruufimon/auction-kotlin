# M2 User-Onboarding — SLO plan

**Scope:** the onboarding journey (register → verify email → sign in → credit-eligibility
assessment → profile → enable selling) plus token refresh, which every later request depends on.
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
| Onboarding API availability: non-5xx responses ÷ all responses | **99.5 %** | 3 h 36 min | `/api/v1/auth/*`, `/api/v1/profile/*` |
| Readiness: `/health/ready` returning 200 | **99.5 %** | 3 h 36 min | probe every 10 s (App Platform) |

Rationale: App Platform redeploys cause brief readiness gaps on a single instance; 99.9 % is not credible until `instance_count: 2`.

### 2.2 Latency (server-side, successful requests, p95 and p99)

| Step | Route | p95 | p99 | Why this number |
|---|---|---|---|---|
| Register | `POST /auth/register` | 800 ms | 1.5 s | Argon2id hashing is deliberately ~300–500 ms on 1 vCPU |
| Verify email | `POST /auth/verify-email` | 300 ms | 600 ms | one hash lookup + state transition + outbox insert |
| Sign in | `POST /auth/signin` | 800 ms | 1.5 s | Argon2id verify + refresh-token insert |
| Refresh | `POST /auth/refresh` | 150 ms | 400 ms | on the hot path of every session; hash lookup + rotate |
| Sign out | `POST /auth/signout` | 150 ms | 400 ms | |
| Read profile | `GET /profile/{id}` | 200 ms | 500 ms | single row, decrypt nothing |
| Update profile (no assessment) | `PUT /profile/{id}` | 300 ms | 600 ms | |
| Credit assessment | `PUT /profile/{id}` with inputs | 500 ms | 1 s | encrypt three fields + policy evaluation + two outbox inserts |
| Enable seller | `POST /profile/{id}/enable-seller` | 300 ms | 600 ms | |

Each latency SLO is met when ≥ 99 % of 5-minute windows in the month satisfy the p95 target.

### 2.3 Asynchronous steps

| SLI | SLO | Source |
|---|---|---|
| Verification email dispatch delay: register commit → email handed to provider | p95 ≤ **60 s**, p99 ≤ 5 min | outbox `created_at` → `published_at` for `user-onboarding.user-registered` |
| Verification email dispatch success | ≥ **99 %** of registrations produce an accepted send within 15 min | email adapter outcome counter |
| Onboarding event delivery lag (all `user-onboarding.*` events) | p95 ≤ **30 s** | outbox lag gauge (§8.4 requires this to be alerted on) |
| Outbox backlog of unpublished onboarding events | < **100** for more than 10 min is an alert | outbox backlog gauge |

### 2.4 Correctness invariants (zero tolerance)

These come straight from §9.1 and USR-006/USR-012. Each is a counter that should stay at 0 in production; any increment pages.

| Invariant | Signal |
|---|---|
| No verification token accepted after expiry or after use | `onboarding_invariant_violations_total{kind="token_reuse_accepted"}` from a post-commit check |
| Refresh-token reuse always revokes the whole family | `auth_refresh_total{outcome="reused"}` must be matched 1:1 by `auth_token_family_revoked_total{reason="reuse"}` |
| Suspended account never obtains a token | `auth_signin_total{outcome="issued", account_state!="active"}` = 0 |
| Assessment raw inputs never logged | log-pipeline rule scanning onboarding log lines for the assessment field names with numeric values; count must be 0 |
| Account activated only with a passing assessment | `onboarding_state_transitions_total{to="active"}` ≤ `credit_assessment_total{outcome="eligible"}` |

### 2.5 Abuse-control signals (monitor, not SLO)

- `ratelimit_hits_total{endpoint}` for sign-in, refresh, verify-email, resend.
- Failed-sign-in ratio over 15 min > 60 % with ≥ 50 attempts → security alert (credential stuffing).
- Verification resend rate per account.

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
| `onboarding_registrations_total` | counter | `outcome` = created \| duplicate \| invalid |
| `onboarding_verification_total` | counter | `outcome` = verified \| expired \| used \| invalid |
| `onboarding_state_transitions_total` | counter | `from`, `to` |
| `auth_signin_total` | counter | `outcome` = issued \| invalid_credentials \| not_active \| suspended \| rate_limited |
| `auth_refresh_total` | counter | `outcome` = rotated \| reused \| expired \| invalid \| not_active |
| `auth_token_family_revoked_total` | counter | `reason` = reuse \| signout_all \| suspension |
| `credit_assessment_total` | counter | `outcome` = eligible \| not_eligible \| invalid, `policy_version` |
| `credit_assessment_duration_seconds` | histogram | — |
| `seller_enable_total` | counter | `outcome` = enabled \| incomplete \| not_active \| already |
| `email_dispatch_seconds` | histogram | `type` = verification |
| `email_dispatch_total` | counter | `type`, `outcome` = accepted \| failed |
| `outbox_backlog` | gauge | `feature` = user-onboarding |
| `outbox_lag_seconds` | histogram | `feature` |
| `ratelimit_hits_total` | counter | `endpoint` |
| `onboarding_invariant_violations_total` | counter | `kind` |
| `password_hash_duration_seconds` | histogram | `op` = hash \| verify (watch Argon2 cost vs CPU) |

### 4.3 Metrics backend (decision needed)

App Platform's built-in monitoring covers CPU, memory and restarts only, and does not scrape Prometheus endpoints. Options:

1. **Grafana Cloud free tier**, remote-write or OTLP from the app. Cheapest, hosted dashboards and alerting. Recommended for now.
2. A **Prometheus + Grafana worker** on a Droplet. More control, more to run.
3. **DigitalOcean Managed OpenSearch** for logs only, with metrics derived from logs. Weakest for burn-rate alerts.

### 4.4 Validation before launch (§8.4)

A k6 scenario covering register, sign-in and refresh at the expected peak (assume 5 registrations/min and 50 sign-ins/min for launch, to be confirmed) run against a staging App Platform app. It must show the latency SLOs hold with Argon2id at the chosen cost on 1 vCPU; if not, lower the cost or size up the instance before launch, not after.

## 5. Dashboard

One "Onboarding" dashboard with four rows: funnel counters and conversion; per-step availability and p95/p99 against SLO lines with remaining error budget; async health (email dispatch delay, outbox lag and backlog); abuse and invariants (rate-limit hits, failed-sign-in ratio, invariant counters).

## 6. Issues to file

| Proposed issue | Milestone | Depends on |
|---|---|---|
| SLO-1 Micrometer + Prometheus `/metrics`, JSON logs with correlation id | M2 | #26 (merged) |
| SLO-2 Onboarding business metrics and invariant counters | M2 | SLO-1, each of #19–#24 |
| SLO-3 Metrics backend decision and remote write from App Platform | M2 | SLO-1 |
| SLO-4 Burn-rate alert rules and onboarding dashboard | M6 or M7 | SLO-2, SLO-3 |
| SLO-5 k6 load test for register / sign-in / refresh against staging | M7 | #19, #21, #22 |

## 7. Open questions

1. Expected launch traffic, to size the low-traffic alert guards and the load test.
2. Argon2id parameters (memory, iterations, parallelism) for 1 vCPU; they set the register and sign-in latency floor.
3. Which email provider, since its acceptance latency bounds the dispatch SLO.
4. Whether `/metrics` is exposed through App Platform internal routing or pushed, which decides between options 1 and 2 in §4.3.
