@CAT-002 @CAT-003 @CAT-004 @CAT-006 @AFA-29 @AFA-34 @api @wip
Feature: Publishing a listing
  A valid draft is published without manual review. It becomes SCHEDULED, or ACTIVE at once
  when its start time has arrived. Invalid prices, missing fields and invalid schedules
  prevent publication (req 9.2).

  Background:
    Given the scheduling limits are: auction length 1 hour to 14 days, start at most 30 days ahead
    And the clock is at "2026-12-01T00:00:00Z"
    And "mina@example.com" is ACTIVE with selling enabled

  Scenario: A complete draft with a future start becomes SCHEDULED
    Given "mina@example.com" has a complete DRAFT listing starting "2026-12-02T00:00:00Z" and ending "2026-12-09T00:00:00Z"
    When they publish it
    Then the response status is 200
    And the listing state is "SCHEDULED"
    And the listing has a "publishedAt" timestamp and a new version
    And exactly one "catalog.listing-published" event is written to the outbox

  Scenario: A complete draft whose start has arrived becomes ACTIVE at once
    Given "mina@example.com" has a complete DRAFT listing starting "2026-12-01T00:00:00Z" and ending "2026-12-03T00:00:00Z"
    When they publish it
    Then the listing state is "ACTIVE"
    And a "catalog.listing-published" and a "catalog.auction-activated" event are written to the outbox

  Scenario Outline: Publication rules reject an invalid draft and name the problem
    Given "mina@example.com" has a complete DRAFT listing
    But the listing has <problem>
    When they publish it
    Then the response status is 422
    And the error code is "PUBLICATION_RULES_FAILED"
    And the error details name the field "<field>" with code "<code>"
    And the listing is still "DRAFT"
    And no event is written to the outbox

    Examples:
      | problem                               | field              | code               |
      | no title                              | title              | REQUIRED           |
      | no description                        | description        | REQUIRED           |
      | no category                           | category           | REQUIRED           |
      | an inactive category                  | category           | INVALID            |
      | no condition                          | condition          | REQUIRED           |
      | no attached image                     | images             | REQUIRED           |
      | no origin details                     | origin             | REQUIRED           |
      | a starting price of 0                 | startingPriceMinor | MUST_BE_POSITIVE   |
      | a negative starting price             | startingPriceMinor | MUST_BE_POSITIVE   |
      | a negative shipping price             | shippingPriceMinor | MUST_NOT_BE_NEGATIVE |
      | no shipping price and no free shipping | shippingPriceMinor | REQUIRED          |
      | an end time before its start time     | endAt              | MUST_BE_AFTER_START |
      | an auction length of 59 minutes       | endAt              | DURATION_TOO_SHORT |
      | an auction length of 14 days 1 minute | endAt              | DURATION_TOO_LONG  |
      | a start time 31 days ahead            | startAt            | START_TOO_FAR_AHEAD |
      | a start time in the past by 2 hours   | startAt            | START_IN_PAST      |

  Scenario: Free shipping replaces the shipping price
    Given "mina@example.com" has a complete DRAFT listing with free shipping and no shipping price
    When they publish it
    Then the response status is 200

  Scenario: Every failed rule is reported at once
    Given "mina@example.com" has a DRAFT listing with only a title
    When they publish it
    Then the response status is 422
    And the error details name every missing required field

  Scenario: Publishing twice does not publish twice
    Given "mina@example.com" has a SCHEDULED listing
    When they publish it again
    Then the response status is 409
    And the error code is "LISTING_ALREADY_PUBLISHED"
    And no second "catalog.listing-published" event is written

  Scenario: A valid listing follows DRAFT, SCHEDULED, ACTIVE
    Given "mina@example.com" has a complete DRAFT listing starting in 2 hours
    When they publish it
    Then the listing state is "SCHEDULED"
    When the clock reaches the start time and the activation job runs
    Then the listing state is "ACTIVE"

  Scenario: Only the owner can publish
    Given "sam@example.com" is ACTIVE with selling enabled
    And "mina@example.com" has a complete DRAFT listing
    When "sam@example.com" publishes "mina@example.com"'s listing
    Then the response status is 403

  Scenario: An unknown listing is not found
    When "mina@example.com" publishes a listing id that does not exist
    Then the response status is 404
    And the error code is "NOT_FOUND"
