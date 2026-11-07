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

Not wired yet. The intended harness, to be added with the first M2 API pull request:

1. Add `io.cucumber:cucumber-java` and `io.cucumber:cucumber-junit-platform-engine`
   to the `testing` bundle in `gradle/libs.versions.toml`.
2. Step definitions live in `features/user-onboarding/src/test/kotlin/.../acceptance/`
   and drive the Ktor app with `testApplication` against the Testcontainers
   PostgreSQL fixture from M1-8 (#16).
3. Keycloak runs as a Testcontainers container importing `deploy/keycloak/realm-gavel.json`;
   mail is captured with a MailHog/Mailpit container as the realm's SMTP; time is controlled
   through an injectable `Clock` in the app and token lifespans in a test realm override.
4. `@ui` scenarios are excluded from `./gradlew test` and run by the browser E2E
   suite (#42) once the web app exists.

Until then these files are the acceptance criteria reviewers check pull requests
against.
