@USR-008 @USR-009 @issue-24 @api
Feature: Seller enablement
  The same account becomes a seller once every configured seller-profile
  and origin-address field is present and the account is ACTIVE.

  Background:
    Given the configured required seller fields are legalName, storeName, contactPhone, payoutAccount
    And the configured required origin-address fields are line1, city, region, postalCode, country

  Scenario: An active user with a complete seller profile can enable selling
    Given "mina@example.com" is ACTIVE with all required seller and origin-address fields filled
    When they call enable-seller on their own profile
    Then the response status is 200
    And the profile has "sellerEnabled" true and a "sellerEnabledAt" timestamp
    And a "user-onboarding.seller-enabled" event is written to the outbox
    And the user id is unchanged, no second account exists

  Scenario: A missing required seller field blocks enablement with the field named
    Given "mina@example.com" is ACTIVE with every required field except payoutAccount
    When they call enable-seller
    Then the response status is 422
    And the error code is "SELLER_PROFILE_INCOMPLETE"
    And the error details name the field "payoutAccount"
    And "sellerEnabled" is still false

  Scenario: A missing origin-address field blocks enablement
    Given "mina@example.com" is ACTIVE with a complete seller profile and no origin postalCode
    When they call enable-seller
    Then the response status is 422
    And the error details name the field "originAddress.postalCode"

  Scenario Outline: Accounts that are not active cannot enable selling
    Given "mina@example.com" is in state "<state>" with a complete seller profile
    When they call enable-seller
    Then the response status is 403
    And the error code is "<code>"

    Examples:
      | state                      | code                               |
      | PENDING_VERIFICATION       | ACCOUNT_PENDING_VERIFICATION       |
      | PENDING_CREDIT_ELIGIBILITY | ACCOUNT_PENDING_CREDIT_ELIGIBILITY |
      | SUSPENDED                  | ACCOUNT_SUSPENDED                  |

  Scenario: Enabling twice is rejected
    Given "mina@example.com" already has "sellerEnabled" true
    When they call enable-seller again
    Then the response status is 422
    And the error code is "SELLER_ALREADY_ENABLED"
    And no second event is written

  Scenario: Enabling another user's selling is forbidden
    Given "mina@example.com" and "sam@example.com" are both ACTIVE
    When "mina@example.com" calls enable-seller on "sam@example.com"'s profile
    Then the response status is 403

  Scenario: Seller enablement unlocks listing publication
    Given "mina@example.com" enabled selling
    When they create and publish a valid listing
    Then publication succeeds

  Scenario: A seller still bids as a buyer with the same account
    Given "mina@example.com" enabled selling
    When they place a bid on another seller's active auction
    Then the bid is accepted

  Scenario: The required field set is configuration, not code
    Given the deployment adds "taxId" to the required seller fields
    And "mina@example.com" is ACTIVE with every previously required field
    When they call enable-seller
    Then the response status is 422
    And the error details name the field "taxId"

  Scenario: End to end, register to seller
    When a visitor registers, verifies their email, passes the credit-eligibility assessment, completes the seller profile and origin address, and calls enable-seller
    Then each step succeeds in order
    And the final profile shows accountState "ACTIVE" and sellerEnabled true
    And the outbox holds, in order, user-registered, email-verified, credit-eligibility-assessed, profile-updated, seller-enabled

  @ui @issue-32
  Scenario: The seller page shows readiness and keeps enable disabled until complete
    Given the web app seller profile page is open with one required field missing
    Then the readiness checklist marks that item incomplete and names the field
    And the enable-selling button is disabled with an explanation
    When the field is filled and saved
    Then the enable-selling button becomes available
