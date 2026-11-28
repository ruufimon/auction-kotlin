@CAT-009 @AFA-34 @api @wip
Feature: Cancelling a listing
  A seller may cancel a listing before its first accepted bid. Cancellation is final.

  Background:
    Given "mina@example.com" is ACTIVE with selling enabled

  Scenario Outline: A listing without bids can be cancelled
    Given "mina@example.com" has a <state> listing with no bids
    When they cancel it
    Then the response status is 200
    And the listing state is "CANCELLED"
    And the listing has a "cancelledAt" timestamp
    And exactly one "catalog.listing-cancelled" event is written to the outbox

    Examples:
      | state     |
      | DRAFT     |
      | SCHEDULED |
      | ACTIVE    |

  @cross-feature
  Scenario: A listing with an accepted bid cannot be cancelled
    Given "mina@example.com" has an ACTIVE listing
    And "sam@example.com" placed an accepted bid on it
    When "mina@example.com" cancels it
    Then the response status is 409
    And the error code is "LISTING_HAS_BIDS"
    And the listing is still "ACTIVE"

  Scenario: Cancellation is final
    Given "mina@example.com" has a CANCELLED listing
    When they publish it
    Then the response status is 409
    And the error code is "LISTING_NOT_EDITABLE"
    When they cancel it again
    Then the response status is 409
    And no second "catalog.listing-cancelled" event is written

  Scenario Outline: An ended listing cannot be cancelled
    Given "mina@example.com" has a <state> listing
    When they cancel it
    Then the response status is 409
    And the error code is "LISTING_NOT_CANCELLABLE"

    Examples:
      | state  |
      | SOLD   |
      | UNSOLD |

  Scenario: Only the owner can cancel
    Given "sam@example.com" is ACTIVE with selling enabled
    And "mina@example.com" has a SCHEDULED listing
    When "sam@example.com" cancels "mina@example.com"'s listing
    Then the response status is 403

  Scenario: A cancelled listing leaves public search
    Given "mina@example.com" has a SCHEDULED listing that appears in public search
    When they cancel it
    Then it no longer appears in public search
    And its public detail returns 404
