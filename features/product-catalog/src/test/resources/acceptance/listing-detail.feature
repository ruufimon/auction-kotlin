@CAT-007 @AFA-35 @api @wip
Feature: Public listing detail
  Anyone can read a public listing in full. Drafts and cancelled listings are not public.

  Background:
    Given "mina@example.com" is ACTIVE with selling enabled and display name "Mina's Finds"

  Scenario Outline: Public states are readable without signing in
    Given "mina@example.com" has a <state> listing "Vintage Leica M3" with 3 attached images
    When an anonymous visitor opens its detail
    Then the response status is 200
    And the detail has state "<state>"
    And the detail has title, description, category, condition, startingPriceMinor, currentPriceMinor, currency, bidCount, shipping, startAt and endAt in UTC
    And the 3 images are listed in display order

    Examples:
      | state     |
      | SCHEDULED |
      | ACTIVE    |
      | SOLD      |
      | UNSOLD    |

  Scenario: The seller appears by display name only
    Given "mina@example.com" has an ACTIVE listing
    When an anonymous visitor opens its detail
    Then the seller summary is "Mina's Finds"
    And the detail contains no seller email, phone, payout account or origin street address
    And the origin is shown as city, region and country only

  Scenario Outline: Non-public listings are not found
    Given "mina@example.com" has a <state> listing
    When an anonymous visitor opens its detail
    Then the response status is 404
    And the error code is "NOT_FOUND"

    Examples:
      | state     |
      | DRAFT     |
      | CANCELLED |

  Scenario: The owner still sees their own draft through the seller API
    Given "mina@example.com" has a DRAFT listing
    When "mina@example.com" lists their listings
    Then the draft is included with its full content

  Scenario: An id that does not exist and a draft look the same
    When an anonymous visitor opens the detail of a listing id that does not exist
    Then the response status is 404
    And the response body has the same shape as for a draft

  @cross-feature
  Scenario: The detail shows the minimum next bid once bidding exists
    Given "mina@example.com" has an ACTIVE listing at current price 125000 with 2 bids
    When an anonymous visitor opens its detail
    Then the detail has "minimumNextBidMinor" equal to the current price plus the minimum increment
