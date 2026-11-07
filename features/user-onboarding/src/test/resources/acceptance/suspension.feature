@USR-010 @api
Feature: Account suspension
  Suspension takes effect immediately across every feature, while existing
  records remain readable for audit and resolution.

  Background:
    Given "mina@example.com" is ACTIVE, seller-enabled, holds refresh token "R1", has an active listing and an open order as seller

  Scenario: Suspension can happen from any pre-suspension state
    Given an account in state "PENDING_VERIFICATION"
    When an operator suspends it with reason "FRAUD_REVIEW"
    Then the account state is "SUSPENDED"
    Given an account in state "PENDING_CREDIT_ELIGIBILITY"
    When an operator suspends it
    Then the account state is "SUSPENDED"
    Given an account in state "ACTIVE"
    When an operator suspends it
    Then the account state is "SUSPENDED"
    And each suspension writes a "user-onboarding.account-suspended" event with the reason

  Scenario: Suspension blocks token refresh immediately
    When an operator suspends "mina@example.com"
    And "R1" is presented to the refresh endpoint
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"
    And all refresh tokens for the account are revoked

  Scenario: Suspension blocks sign-in
    When an operator suspends "mina@example.com"
    And they sign in with the correct password
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"

  Scenario: Suspension blocks bidding
    When an operator suspends "mina@example.com"
    And they place a bid with a still-valid access token
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"

  Scenario: Suspension blocks listing publication
    When an operator suspends "mina@example.com"
    And they publish a draft listing
    Then the response status is 403

  Scenario: Suspension blocks fulfilment updates
    When an operator suspends "mina@example.com"
    And they mark their open order as shipped
    Then the response status is 403

  Scenario: Existing records remain available
    When an operator suspends "mina@example.com"
    Then their listings, bids and orders are still readable by operators
    And the account row is not deleted
    And the audit log keeps every prior entry

  Scenario: Reactivation requires verified email and passed eligibility
    Given "sam@example.com" was suspended while in "PENDING_CREDIT_ELIGIBILITY"
    When an operator reactivates "sam@example.com"
    Then the response status is 422
    And the error code is "REACTIVATION_PRECONDITIONS_NOT_MET"
    Given "mina@example.com" was suspended while "ACTIVE"
    When an operator reactivates "mina@example.com"
    Then the account state is "ACTIVE"
    And a "user-onboarding.account-reactivated" event is written

  Scenario: Reactivation does not restore old sessions
    Given "mina@example.com" was suspended and reactivated
    When "R1" is presented to the refresh endpoint
    Then the response status is 401

  @ui @issue-31 @issue-32
  Scenario: A suspended user is signed out with an explanation
    Given the web app has a session for "mina@example.com"
    When the account is suspended and the next refresh fails with "ACCOUNT_SUSPENDED"
    Then the app clears the session and shows a suspension message with a support contact
