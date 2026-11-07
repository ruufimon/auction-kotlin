@USR-005 @issue-21 @api
Feature: Sign in
  Only a verified, credit-eligible, non-suspended account receives tokens.

  Background:
    Given access tokens expire after 15 minutes
    And refresh tokens expire after 30 days

  Scenario: An active account receives an access token and a refresh token
    Given "mina@example.com" is ACTIVE with password "correct-horse-battery"
    When they sign in with "mina@example.com" and "correct-horse-battery"
    Then the response status is 200
    And the response contains a JWT access token with claims "user_id" and "email" and an expiry 15 minutes ahead
    And the response contains an opaque refresh token
    And the refresh token is stored as a hash with an expiry 30 days ahead
    And a "user-onboarding.signed-in" audit event is recorded

  Scenario: A wrong password is rejected
    Given "mina@example.com" is ACTIVE with password "correct-horse-battery"
    When they sign in with "mina@example.com" and "wrong-password-here"
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS"
    And no refresh token is created

  Scenario: An unknown email is rejected with the same response as a wrong password
    When someone signs in with "nobody@example.com" and "any-password-at-all"
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS"
    And the response body and timing do not reveal that the email is unregistered

  Scenario Outline: Accounts that are not active cannot sign in
    Given "mina@example.com" is in state "<state>" with password "correct-horse-battery"
    When they sign in with "mina@example.com" and "correct-horse-battery"
    Then the response status is 403
    And the error code is "<code>"
    And no tokens are issued

    Examples:
      | state                      | code                               |
      | PENDING_VERIFICATION       | ACCOUNT_PENDING_VERIFICATION       |
      | PENDING_CREDIT_ELIGIBILITY | ACCOUNT_PENDING_CREDIT_ELIGIBILITY |
      | SUSPENDED                  | ACCOUNT_SUSPENDED                  |

  Scenario: A failed eligibility assessment keeps sign-in blocked with the stable reason
    Given "mina@example.com" failed the credit-eligibility assessment with reason "BELOW_MINIMUM_SCORE"
    When they sign in with the correct password
    Then the response status is 403
    And the error code is "ACCOUNT_PENDING_CREDIT_ELIGIBILITY"
    And the error details include "eligibilityReason" = "BELOW_MINIMUM_SCORE"

  Scenario: Sign-in attempts are rate limited
    Given "mina@example.com" is ACTIVE
    When 10 sign-in attempts with a wrong password are made within one minute
    Then the next attempt returns status 429
    And the error code is "RATE_LIMITED"
    And the response carries a "Retry-After" header

  @ui @issue-31
  Scenario: Sign-in page maps every error to a message and next step
    Given the web app sign-in page is open
    Then "INVALID_CREDENTIALS" shows a credentials message with a password-reset link
    And "ACCOUNT_PENDING_VERIFICATION" shows a resend-verification action
    And "ACCOUNT_PENDING_CREDIT_ELIGIBILITY" shows a link to the eligibility check
    And "ACCOUNT_SUSPENDED" shows a support contact
    And "RATE_LIMITED" shows the wait time
