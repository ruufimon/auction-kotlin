@USR-003 @USR-004 @issue-20 @api
Feature: Email verification
  A verification token works once, expires, and moves the account to
  PENDING_CREDIT_ELIGIBILITY without activating it.

  Background:
    Given verification tokens expire after 24 hours
    And "mina@example.com" registered at "2026-11-07T10:00:00Z" and received a verification token

  Scenario: A valid token verifies the email and advances the account state
    When the token is submitted at "2026-11-07T10:30:00Z"
    Then the response status is 200
    And the account has "emailVerified" true with an "emailVerifiedAt" timestamp
    And the account state is "PENDING_CREDIT_ELIGIBILITY"
    And the token is marked consumed
    And a "user-onboarding.email-verified" event is written to the outbox

  Scenario: Verification alone does not activate the account
    Given the token has been submitted successfully
    When that user attempts to place a bid on an active auction
    Then the response status is 403
    And the error code is "ACCOUNT_NOT_ACTIVE"
    When that user attempts to publish a listing
    Then the response status is 403

  Scenario: A token cannot be used twice
    Given the token has been submitted successfully
    When the same token is submitted again
    Then the response status is 422
    And the error code is "VERIFICATION_TOKEN_USED"
    And the account state is still "PENDING_CREDIT_ELIGIBILITY"

  Scenario: An expired token is rejected
    When the token is submitted at "2026-11-08T10:00:01Z"
    Then the response status is 404
    And the error code is "VERIFICATION_TOKEN_EXPIRED"
    And the account state is still "PENDING_VERIFICATION"

  Scenario: An unknown or tampered token is rejected without revealing whether the email exists
    When a token "not-a-real-token" is submitted for "mina@example.com"
    Then the response status is 400
    And the error code is "VERIFICATION_TOKEN_INVALID"
    And the response is identical for an email that was never registered

  Scenario: Resending issues a new token and invalidates the previous one
    When the user requests a new verification email
    Then a second verification email is sent
    And the first token is rejected with "VERIFICATION_TOKEN_INVALID"
    And the second token verifies the email

  Scenario: Verification is recorded for audit
    When the token is submitted successfully
    Then the audit log holds an entry with actor = the user, action = "email.verified" and the aggregate id
    And the entry does not contain the token value

  @ui @issue-30
  Scenario: Verify-email landing page distinguishes outcomes
    Given the web app verification landing page is opened with a token
    Then a valid token shows a success state and a call to action for the eligibility check
    And an expired token shows an explanation and a resend action
    And a used token shows an explanation and a sign-in action
