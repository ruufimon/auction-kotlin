@USR-004 @USR-008 @USR-010 @AFA-32 @AFA-91 @api @wip
Feature: Seller eligibility in the catalog
  The catalog decides who may sell from its own projection, fed by onboarding events through
  the outbox poller. It never imports or queries the onboarding feature.

  Scenario: Enabling selling in onboarding lets the user create listings
    Given "mina@example.com" is ACTIVE without selling enabled
    When "mina@example.com" enables selling in onboarding
    And the outbox poller has run
    And "mina@example.com" creates a listing
    Then the response status is 201

  Scenario: Events reach the catalog within seconds
    Given the outbox poller runs every 2 seconds
    When "mina@example.com" enables selling in onboarding
    Then within 5 seconds the catalog treats "mina@example.com" as an eligible seller

  Scenario: A suspended seller is blocked on the next request
    Given "mina@example.com" is ACTIVE with selling enabled and has a DRAFT listing
    When an operator suspends "mina@example.com"
    And the outbox poller has run
    Then each of these by "mina@example.com" returns 403 with code "ACCOUNT_SUSPENDED":
      | action             |
      | create a listing   |
      | edit the draft     |
      | publish the draft  |
      | cancel the draft   |
      | upload an image    |

  # Open decision (docs/M3_CATALOG_ACCEPTANCE.md, gap 1): the catalog learns of a suspension
  # only when the event arrives, so this needs a shared account-state check or an accepted lag.
  @gap
  Scenario: A token issued before suspension is refused even before the event arrives
    Given "mina@example.com" is ACTIVE with selling enabled and holds a valid access token
    When an operator suspends "mina@example.com"
    And the outbox poller has not run yet
    And "mina@example.com" publishes a listing with that token
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"

  Scenario: A suspended seller's public listings stay visible for audit
    Given "mina@example.com" has an ACTIVE listing that appears in public search
    When an operator suspends "mina@example.com"
    And the outbox poller has run
    Then the listing still appears in public search

  Scenario: Reactivation restores selling
    Given "mina@example.com" was suspended while selling was enabled
    When an operator reactivates "mina@example.com"
    And the outbox poller has run
    And "mina@example.com" creates a listing
    Then the response status is 201

  Scenario: Delivering the same event twice leaves one projection row
    Given a "user-onboarding.seller-enabled" event for "mina@example.com"
    When the catalog handler receives it twice
    Then the catalog has exactly one seller record for "mina@example.com"

  Scenario: Events for one user are applied in order
    Given "mina@example.com" enabled selling, was suspended, then reactivated, in that order
    When the outbox poller delivers all three events
    Then the catalog treats "mina@example.com" as an eligible seller

  Scenario: A failing handler does not block other events
    Given the catalog handler fails once for the event about "mina@example.com"
    And a later event about "sam@example.com" is in the outbox
    When the outbox poller runs
    Then "sam@example.com"'s event is delivered
    And "mina@example.com"'s event has 1 delivery attempt and is delivered on the next run

  Scenario: Two pollers never deliver the same event in the same run
    Given 200 undelivered events are in the outbox
    When two outbox pollers run at the same time
    Then every event is delivered exactly once

  Scenario: Sellers enabled before the poller existed are backfilled
    Given "mina@example.com" enabled selling before the catalog projection existed
    When the backfill runs
    Then the catalog treats "mina@example.com" as an eligible seller
