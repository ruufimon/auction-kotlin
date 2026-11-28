@CAT-010 @AFA-36 @AFA-91 @api @wip
Feature: Scheduled activation and closing
  The scheduler activates due SCHEDULED listings and closes due ACTIVE ones. Jobs are safe to
  retry, safe with two app instances, and survive restarts (req 5.2, 9.2).

  Background:
    Given the clock is at "2026-12-01T00:00:00Z"
    And "mina@example.com" is ACTIVE with selling enabled

  Scenario: A due SCHEDULED listing becomes ACTIVE
    Given "mina@example.com" has a SCHEDULED listing starting "2026-12-01T00:00:00Z"
    When the activation job runs
    Then the listing state is "ACTIVE"
    And exactly one "catalog.auction-activated" event is written to the outbox

  Scenario: A listing that is not yet due stays SCHEDULED
    Given "mina@example.com" has a SCHEDULED listing starting "2026-12-01T00:00:01Z"
    When the activation job runs
    Then the listing state is "SCHEDULED"
    And no event is written to the outbox

  Scenario: Closing without bids produces UNSOLD
    Given "mina@example.com" has an ACTIVE listing ending "2026-12-01T00:00:00Z" with no bids
    When the closing job runs
    Then the listing state is "UNSOLD"
    And exactly one "catalog.auction-unsold" event is written to the outbox

  @cross-feature
  Scenario: Closing with bids produces SOLD
    Given "mina@example.com" has an ACTIVE listing ending "2026-12-01T00:00:00Z"
    And "sam@example.com" holds the highest accepted bid of 130000
    When the closing job runs
    Then the listing state is "SOLD"
    And exactly one "catalog.auction-sold" event is written with buyer "sam@example.com" and winningAmountMinor 130000

  Scenario: An ACTIVE listing before its end time stays open
    Given "mina@example.com" has an ACTIVE listing ending "2026-12-01T00:00:01Z"
    When the closing job runs
    Then the listing state is "ACTIVE"

  Scenario: Running a job twice transitions once
    Given "mina@example.com" has a SCHEDULED listing starting "2026-12-01T00:00:00Z"
    When the activation job runs twice
    Then the listing state is "ACTIVE"
    And exactly one "catalog.auction-activated" event is written to the outbox

  Scenario: Two app instances running the same job transition once
    Given "mina@example.com" has 50 ACTIVE listings ending "2026-12-01T00:00:00Z" with no bids
    When two closing jobs run at the same time
    Then all 50 listings are "UNSOLD"
    And exactly 50 "catalog.auction-unsold" events are written to the outbox

  Scenario: Listings that fell due while the app was down are handled on the next run
    Given "mina@example.com" has a SCHEDULED listing starting "2026-11-30T20:00:00Z" and ending "2026-11-30T23:00:00Z"
    And the app was stopped from "2026-11-30T19:00:00Z" until now
    When the activation job and then the closing job run
    Then the listing state is "UNSOLD"
    And one "catalog.auction-activated" and one "catalog.auction-unsold" event are written, in that order

  Scenario: A batch is bounded and handles the most overdue first
    Given the job batch size is 100
    And 150 SCHEDULED listings are due, with start times one minute apart
    When the activation job runs once
    Then the 100 earliest listings are "ACTIVE" and the other 50 are still "SCHEDULED"
    When the activation job runs again
    Then all 150 listings are "ACTIVE"

  Scenario: A cancelled listing is never activated or closed
    Given "mina@example.com" has a CANCELLED listing whose start time has passed
    When the activation job and then the closing job run
    Then the listing state is "CANCELLED"
    And no event is written to the outbox
