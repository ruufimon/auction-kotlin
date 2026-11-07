@USR-010 @issue-22 @api
Feature: Account suspension
  Suspension takes effect immediately on both sides: the application rejects existing tokens,
  Keycloak refuses new sign-ins and refreshes. Existing records remain readable.

  Background:
    Given "mina@example.com" is ACTIVE, seller-enabled, holds a valid access token and refresh token "R1", has an active listing and an open order as seller

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

  Scenario: Suspension disables the provider account
    When an operator suspends "mina@example.com"
    Then the application asks the identity provider to disable the user and end its sessions

  @provider
  Scenario: A suspended provider account cannot refresh or sign in
    When an operator suspends "mina@example.com"
    Then presenting "R1" to Keycloak returns invalid_grant
    And signing in at Keycloak with the correct password is refused

  @cross-feature
  Scenario: A still-valid access token is rejected after suspension
    When an operator suspends "mina@example.com"
    And the pre-suspension access token is used to place a bid
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"

  Scenario: A still-valid access token cannot change the account after suspension
    When an operator suspends "mina@example.com"
    And the pre-suspension access token is used to update their profile
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"
    When the pre-suspension access token is used to read their profile
    Then the response status is 200

  @cross-feature
  Scenario: Suspension blocks listing publication and fulfilment updates
    When an operator suspends "mina@example.com"
    And they publish a draft listing
    Then the response status is 403
    When they mark their open order as shipped
    Then the response status is 403

  Scenario: Application state flips even if the provider call fails
    Given the Keycloak admin API is unreachable
    When an operator suspends "mina@example.com"
    Then the application state is "SUSPENDED" and tokens are rejected immediately
    And the failed provider call is audited and the account-suspended event waits in the outbox for retry

  @cross-feature
  Scenario: Existing records remain available
    When an operator suspends "mina@example.com"
    Then their listings, bids and orders are still readable by operators

  Scenario: Suspension deletes nothing
    When an operator suspends "mina@example.com"
    Then neither the application account nor the Keycloak user is deleted
    And the audit log keeps every prior entry

  Scenario: Reactivation requires verified email and passed eligibility
    Given "sam@example.com" was suspended while in "PENDING_CREDIT_ELIGIBILITY"
    When an operator reactivates "sam@example.com"
    Then the response status is 422
    And the error code is "REACTIVATION_PRECONDITIONS_NOT_MET"
    Given "mina@example.com" was suspended while "ACTIVE"
    When an operator reactivates "mina@example.com"
    Then the account state is "ACTIVE" and the Keycloak user is enabled
    And a "user-onboarding.account-reactivated" event is written

  @provider
  Scenario: Reactivation does not restore old sessions
    Given "mina@example.com" was suspended and reactivated
    When "R1" is presented to Keycloak
    Then Keycloak returns invalid_grant

  @ui @issue-31 @issue-32
  Scenario: A suspended user is signed out with an explanation
    Given the web app has a session for "mina@example.com"
    When the account is suspended and the next API call fails with "ACCOUNT_SUSPENDED"
    Then the app clears the session and shows a suspension message with a support contact
