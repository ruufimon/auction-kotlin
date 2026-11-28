@CAT-001 @CAT-002 @CAT-005 @AFA-33 @api @wip
Feature: Listing drafts
  An eligible seller creates a draft for exactly one physical item, edits it while it is
  DRAFT or SCHEDULED, and lists their own listings. Publication rules are not enforced
  until publish (publication.feature).

  Background:
    Given "mina@example.com" is ACTIVE with selling enabled
    And the catalog has the categories "electronics", "collectibles", "home"

  Scenario: A seller creates a draft with partial content
    When "mina@example.com" creates a listing with title "Vintage Leica M3" and an Idempotency-Key
    Then the response status is 201
    And the listing state is "DRAFT"
    And the response carries an opaque "listingId" and "version" 1
    And no event is written to the outbox

  Scenario: Money and times use minor units and UTC
    When "mina@example.com" creates a listing with starting price 125000 and start time "2026-12-01T09:00:00+07:00"
    Then the listing has "startingPriceMinor" 125000 and "currency" "USD"
    And the listing "startAt" is "2026-12-01T02:00:00Z"

  Scenario: Repeating create with the same key returns the same listing
    Given "mina@example.com" created a listing with Idempotency-Key "k-1"
    When they repeat the same request with Idempotency-Key "k-1"
    Then the response status is 201
    And the same "listingId" is returned
    And "mina@example.com" has exactly one listing

  Scenario: Reusing a key with a different body is a conflict
    Given "mina@example.com" created a listing with Idempotency-Key "k-1"
    When they create a listing with a different title and Idempotency-Key "k-1"
    Then the response status is 409
    And the error code is "IDEMPOTENCY_KEY_REUSED"

  Scenario: Create without an Idempotency-Key is rejected
    When "mina@example.com" creates a listing without an Idempotency-Key
    Then the response status is 400
    And the error code is "IDEMPOTENCY_KEY_REQUIRED"

  Scenario: A seller edits their draft and gets a new version
    Given "mina@example.com" has a DRAFT listing at version 1
    When they update its title to "Leica M3, 1957" with version 1
    Then the response status is 200
    And the listing has version 2

  Scenario: A stale version is rejected
    Given "mina@example.com" has a DRAFT listing at version 2
    When they update it with version 1
    Then the response status is 409
    And the error code is "VERSION_CONFLICT"

  Scenario: A SCHEDULED listing can still be edited, including its economics
    Given "mina@example.com" has a SCHEDULED listing
    When they change its starting price to 150000
    Then the response status is 200
    And the listing is still "SCHEDULED"

  Scenario Outline: Economic fields are locked once the auction is ACTIVE
    Given "mina@example.com" has an ACTIVE listing
    When they change its "<field>"
    Then the response status is 422
    And the error code is "LISTING_FIELD_LOCKED"
    And the error details name the field "<field>"

    Examples:
      | field              |
      | startingPriceMinor |
      | shippingPriceMinor |
      | freeShipping       |
      | startAt            |
      | endAt              |

  Scenario: Non-economic fields stay editable while ACTIVE
    Given "mina@example.com" has an ACTIVE listing
    When they change its description
    Then the response status is 200

  Scenario Outline: Listings that ended or were cancelled cannot be edited
    Given "mina@example.com" has a <state> listing
    When they change its description
    Then the response status is 409
    And the error code is "LISTING_NOT_EDITABLE"

    Examples:
      | state     |
      | SOLD      |
      | UNSOLD    |
      | CANCELLED |

  Scenario: Another user cannot edit my listing
    Given "sam@example.com" is ACTIVE with selling enabled
    And "mina@example.com" has a DRAFT listing
    When "sam@example.com" updates "mina@example.com"'s listing
    Then the response status is 403
    And the error code is "FORBIDDEN"

  Scenario: A user without selling enabled cannot create a listing
    Given "pat@example.com" is ACTIVE without selling enabled
    When "pat@example.com" creates a listing
    Then the response status is 403
    And the error code is "SELLER_NOT_ENABLED"

  Scenario: Creating a listing requires authentication
    When an anonymous visitor creates a listing
    Then the response status is 401

  Scenario: A seller lists their own listings in every state, filterable and bounded
    Given "mina@example.com" has 3 DRAFT, 2 SCHEDULED and 1 CANCELLED listings
    And "sam@example.com" has 2 DRAFT listings
    When "mina@example.com" lists their listings with state "DRAFT"
    Then exactly 3 listings are returned, all owned by "mina@example.com"
    When "mina@example.com" lists their listings with limit 51
    Then the response status is 400
    And the error code is "PAGE_SIZE_TOO_LARGE"
