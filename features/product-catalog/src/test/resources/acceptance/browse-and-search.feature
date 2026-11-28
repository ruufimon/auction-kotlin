@CAT-007 @CAT-008 @AFA-31 @AFA-35 @api @wip
Feature: Browsing and searching public listings
  Visitors, signed in or not, browse and search SCHEDULED, ACTIVE, SOLD and UNSOLD listings
  by text, category, status, price range and end time, with stable pagination and documented
  sorting. Page size is 20 by default and 50 at most.

  Background:
    Given the clock is at "2026-12-01T00:00:00Z"
    And the catalog has the categories "electronics", "collectibles", "home"
    And these listings exist:
      | title                 | state     | category     | currentPriceMinor | publishedAt          | endAt                |
      | Vintage Leica M3      | ACTIVE    | electronics  | 125000            | 2026-11-25T00:00:00Z | 2026-12-02T00:00:00Z |
      | Brass desk lamp       | ACTIVE    | home         | 4500              | 2026-11-28T00:00:00Z | 2026-12-05T00:00:00Z |
      | First-edition comic   | SCHEDULED | collectibles | 20000             | 2026-11-30T00:00:00Z | 2026-12-10T00:00:00Z |
      | Walnut side table     | SOLD      | home         | 18000             | 2026-11-20T00:00:00Z | 2026-11-30T00:00:00Z |
      | Rotary phone          | UNSOLD    | electronics  | 3000              | 2026-11-19T00:00:00Z | 2026-11-29T00:00:00Z |
      | Unfinished draft      | DRAFT     | home         | 1000              |                      | 2026-12-20T00:00:00Z |
      | Withdrawn record deck | CANCELLED | electronics  | 9000              | 2026-11-26T00:00:00Z | 2026-12-04T00:00:00Z |

  Scenario: An anonymous visitor can search without signing in
    When an anonymous visitor searches with no filters
    Then the response status is 200
    And no Authorization header was needed

  Scenario: Only public states appear
    When an anonymous visitor searches with no filters
    Then the results contain "Vintage Leica M3", "Brass desk lamp", "First-edition comic", "Walnut side table", "Rotary phone"
    And the results do not contain "Unfinished draft" or "Withdrawn record deck"

  Scenario Outline: Each filter narrows the results
    When an anonymous visitor searches with <filter>
    Then the results are exactly <titles>

    Examples:
      | filter                                     | titles                                   |
      | q "leica"                                  | "Vintage Leica M3"                       |
      | q "desk lamp"                              | "Brass desk lamp"                        |
      | category "home"                            | "Brass desk lamp", "Walnut side table"   |
      | status "ACTIVE"                            | "Vintage Leica M3", "Brass desk lamp"    |
      | status "SOLD,UNSOLD"                       | "Walnut side table", "Rotary phone"      |
      | minPrice 5000 and maxPrice 30000           | "First-edition comic", "Walnut side table" |
      | endsBefore "2026-12-03T00:00:00Z"          | "Vintage Leica M3", "Walnut side table", "Rotary phone" |
      | category "electronics" and status "ACTIVE" | "Vintage Leica M3"                       |

  Scenario: Asking for a non-public status is rejected, not silently ignored
    When an anonymous visitor searches with status "DRAFT"
    Then the response status is 400
    And the error code is "VALIDATION_FAILED"
    And the error details name the field "status"

  Scenario Outline: Documented sort orders
    When an anonymous visitor searches with status "ACTIVE,SCHEDULED" and sort "<sort>"
    Then the results are in the order <order>

    Examples:
      | sort        | order                                                       |
      | ENDING_SOON | "Vintage Leica M3", "Brass desk lamp", "First-edition comic" |
      | NEWEST      | "First-edition comic", "Brass desk lamp", "Vintage Leica M3" |
      | PRICE_ASC   | "Brass desk lamp", "First-edition comic", "Vintage Leica M3" |
      | PRICE_DESC  | "Vintage Leica M3", "First-edition comic", "Brass desk lamp" |

  Scenario: Ended listings sort after open ones under ENDING_SOON
    When an anonymous visitor searches with no filters and sort "ENDING_SOON"
    Then the open listings come first by end time, then the ended ones by most recently ended
    And the results are in the order "Vintage Leica M3", "Brass desk lamp", "First-edition comic", "Walnut side table", "Rotary phone"

  Scenario: ENDING_SOON is the default sort
    When an anonymous visitor searches with no sort
    Then the results are ordered as with sort "ENDING_SOON"

  Scenario: The default page size is 20 and the maximum is 50
    Given 60 ACTIVE listings exist
    When an anonymous visitor searches with no limit
    Then 20 results are returned with a "nextCursor"
    When an anonymous visitor searches with limit 50
    Then 50 results are returned
    When an anonymous visitor searches with limit 51
    Then the response status is 400
    And the error code is "PAGE_SIZE_TOO_LARGE"

  Scenario: Pagination is stable while listings are added
    Given 45 ACTIVE listings exist
    When an anonymous visitor reads the first page of 20 sorted by ENDING_SOON
    And 5 new ACTIVE listings are published that end before every listing already shown
    And the visitor reads every following page with the returned cursor
    Then every listing that existed before the first page appears exactly once across the pages
    And no listing appears twice

  Scenario: Equal sort keys still page deterministically
    Given 30 ACTIVE listings exist with the same end time
    When an anonymous visitor reads all pages of 10 sorted by ENDING_SOON
    Then 30 distinct listings are returned in the same order on every run

  Scenario Outline: Malformed parameters are rejected
    When an anonymous visitor searches with <bad parameter>
    Then the response status is 400
    And the error code is "<code>"

    Examples:
      | bad parameter                    | code              |
      | limit 0                          | VALIDATION_FAILED |
      | minPrice -1                      | VALIDATION_FAILED |
      | minPrice 5000 and maxPrice 1000  | VALIDATION_FAILED |
      | sort "CHEAPEST"                  | VALIDATION_FAILED |
      | endsBefore "tomorrow"            | VALIDATION_FAILED |
      | a cursor that was tampered with  | INVALID_CURSOR    |

  Scenario: Search results carry what a result card needs and nothing private
    When an anonymous visitor searches with q "leica"
    Then each result has listingId, title, category, state, currentPriceMinor, currency, bidCount, endAt and a primary image URL
    And no result contains the seller's email, payout account or origin street address

  Scenario: Active categories are listed for the filter
    Given the category "garden" exists but is inactive
    When an anonymous visitor lists the categories
    Then the response status is 200
    And the categories are "electronics", "collectibles", "home" in display order
    And "garden" is not listed

  Scenario: Public reads can be cached briefly
    When an anonymous visitor searches with no filters
    Then the response has a "Cache-Control" header allowing public caching for at most 30 seconds

  Scenario: Anonymous search is rate limited
    Given the anonymous search limit is 120 requests per minute per client address
    When one client address sends 121 searches within a minute
    Then the last response status is 429
    And the error code is "RATE_LIMITED"
