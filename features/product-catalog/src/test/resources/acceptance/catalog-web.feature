@ui @wip
Feature: Catalog in the web app
  Browser checks for the marketplace, the listing detail page and the seller's listing pages.
  Run by Playwright in web/e2e against the local stack, not by the Cucumber suite.

  @AFA-17 @CAT-007 @CAT-008
  Scenario: A visitor browses the marketplace without signing in
    Given an anonymous visitor opens "/marketplace"
    Then they see listing cards with image, title, current price and time remaining
    And 20 cards per page with a control to load the next page

  @AFA-17
  Scenario: Search and filters live in the URL
    Given an anonymous visitor searches "leica" in category "electronics" sorted by price ascending
    Then the URL carries q, category and sort
    When they reload the page or share the URL
    Then the same results and filters are shown

  @AFA-17
  Scenario: Empty and loading states
    When a search matches nothing
    Then the marketplace shows an empty state with a way to clear filters
    And while results load, placeholders are shown instead of a blank page

  @AFA-18
  Scenario: The detail page shows everything needed to decide to bid
    Given an ACTIVE listing with 3 images
    When a visitor opens its detail page
    Then they see the image gallery, description, condition, seller display name, current price, bid count and a countdown
    And times are shown in the visitor's local time zone

  @AFA-18
  Scenario Outline: Ended and cancelled listings offer no bid action
    Given a <state> listing
    When a signed-in buyer opens its detail page
    Then no bid button is shown and the state is explained

    Examples:
      | state  |
      | SOLD   |
      | UNSOLD |

  @AFA-18
  Scenario: Visitors who are not active buyers are sent to sign in or finish onboarding
    Given an ACTIVE listing
    When an anonymous visitor chooses to bid
    Then they are sent to sign in and then back to the listing

  @AFA-19
  Scenario: A seller creates, fills and publishes a listing
    Given "mina@example.com" is signed in with selling enabled
    When they create a listing, upload 2 images, reorder them, fill every field and publish
    Then the listing appears under "My listings" as SCHEDULED
    And a signed-out visitor finds it in the marketplace

  @AFA-19
  Scenario: Server validation errors appear next to the fields
    Given "mina@example.com" is editing a draft with a starting price of 0
    When they publish
    Then the error for starting price is shown next to that field

  @AFA-19
  Scenario: Seller pages are closed to users who cannot sell
    Given "pat@example.com" is signed in without selling enabled
    When they open "/sell"
    Then they are sent to the seller setup page

  @AFA-19
  Scenario: Cancel asks for confirmation without a browser dialog
    Given "mina@example.com" has a SCHEDULED listing
    When they choose cancel
    Then an in-page confirmation explains that cancelling is final
    When they confirm
    Then the listing shows as CANCELLED
