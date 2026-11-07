# User Onboarding Feature - Domain Events

## Overview
User onboarding owns the application account: its state machine
(`PENDING_VERIFICATION -> PENDING_CREDIT_ELIGIBILITY -> ACTIVE -> SUSPENDED`), credit
eligibility, profile and seller status. Credentials, tokens and email verification are
Keycloak's (requirement.md §5.1, PR #60); the application learns about them from token claims.

Every event is published through the kernel outbox in the same transaction as the account
change, with `aggregateType = "account"` and `aggregateId` = the account row id. Payloads carry
the public `userId` (`usr_…`), never raw assessment inputs, scores or provider secrets.
Constants and payload classes: `src/main/kotlin/com/gavel/onboarding/api/UserOnboardingEvents.kt`.

## Events Published

| Type | Payload v1 | When | Consumers |
|---|---|---|---|
| `user-onboarding.account-created` | `AccountCreatedV1(userId, email, emailVerified, accountState, createdAt)` | First authenticated request creates the application account (USR-013). State is `PENDING_VERIFICATION` or `PENDING_CREDIT_ELIGIBILITY` depending on the `email_verified` claim. | audit, analytics |
| `user-onboarding.email-verified` | `EmailVerifiedV1(userId, email, verifiedAt)` | A request arrives with `email_verified=true` for a `PENDING_VERIFICATION` account; it moves to `PENDING_CREDIT_ELIGIBILITY`. Verification alone never activates. | bidding (not yet eligible), audit |
| `user-onboarding.credit-eligibility-assessed` | `CreditEligibilityAssessedV1(userId, outcome, reason, policyVersion, assessedAt)` | An assessment was scored, either outcome (USR-012). `ELIGIBLE` also moves the account to `ACTIVE`. | bidding, catalog (eligibility), audit |
| `user-onboarding.profile-updated` | `ProfileUpdatedV1(userId, version, updatedAt)` | Display name, contact, shipping address, seller profile or origin address changed (USR-007). | catalog (seller display), settlement (shipping) |
| `user-onboarding.seller-enabled` | `SellerEnabledV1(userId, storeName, enabledAt)` | All configured seller and origin-address fields present on an `ACTIVE` account (USR-008). | catalog (#49: may create listings) |
| `user-onboarding.account-suspended` | `AccountSuspendedV1(userId, suspendedAt, reason)` | Operator suspension from any pre-suspension state (USR-010). The provider-sync handler disables the Keycloak user; retried through the outbox if the admin API fails. | bidding, catalog, settlement (block actions), provider sync |
| `user-onboarding.account-reactivated` | `AccountReactivatedV1(userId, reactivatedAt)` | Operator reactivation of a suspended account with verified email and passed eligibility. | same as suspended, provider sync |

## Events Consumed

None yet. Planned: a provider-sync subscriber for `account-suspended` / `account-reactivated`
that calls the Keycloak admin API (issue #22, after #19), delivered by the M6 outbox dispatcher.

## Ordering guarantees

Within one account the outbox order follows the request order, because every change runs in
one transaction with its events: `account-created` is always first, `email-verified` precedes
`credit-eligibility-assessed`, and `seller-enabled` follows the `profile-updated` that
completed the seller profile (acceptance: seller-enablement.feature, "End to end").

## Idempotency

Handlers must tolerate replays: all payloads carry the account's current state or a timestamp,
so a consumer can compare against what it already applied.
