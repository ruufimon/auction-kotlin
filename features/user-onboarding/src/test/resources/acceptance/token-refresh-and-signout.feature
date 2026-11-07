@USR-005 @USR-006 @issue-22 @issue-23 @api
Feature: Token refresh and sign-out
  Refresh tokens rotate on every use, reuse burns the whole family,
  and sign-out revokes immediately.

  Background:
    Given "mina@example.com" is ACTIVE and signed in, holding refresh token "R1" in family "F1"

  Scenario: Refresh rotates the token
    When "R1" is presented to the refresh endpoint
    Then the response status is 200
    And a new access token is issued
    And a new refresh token "R2" in family "F1" is issued
    And "R1" is marked revoked
    And a "user-onboarding.token-rotated" audit event is recorded

  Scenario: A rotated token cannot be reused, and reuse invalidates the family
    Given "R1" was rotated into "R2"
    When "R1" is presented again
    Then the response status is 401
    And the error code is "REFRESH_TOKEN_REUSED"
    And every token in family "F1", including "R2", is revoked
    When "R2" is presented
    Then the response status is 401

  Scenario: An expired refresh token is rejected
    Given "R1" expired one second ago
    When "R1" is presented
    Then the response status is 401
    And the error code is "REFRESH_TOKEN_EXPIRED"

  Scenario: An unknown refresh token is rejected
    When a refresh token that was never issued is presented
    Then the response status is 401
    And the error code is "REFRESH_TOKEN_INVALID"

  Scenario: Refresh is refused once the account is no longer active
    Given the account is suspended after "R1" was issued
    When "R1" is presented
    Then the response status is 403
    And the error code is "ACCOUNT_SUSPENDED"
    And "R1" is revoked

  Scenario: Sign-out revokes the presented refresh token
    When "R1" is presented to the sign-out endpoint
    Then the response status is 204
    And "R1" is marked revoked
    When "R1" is presented to the refresh endpoint
    Then the response status is 401

  Scenario: Sign-out everywhere revokes every token for the user
    Given the user also holds refresh token "R9" in family "F2" from another device
    When the user signs out with "allDevices" = true
    Then "R1" and "R9" are both revoked

  Scenario: Refresh tokens are never stored or logged in plaintext
    Then the refresh_tokens table holds only hashes of "R1"
    And the raw value of "R1" appears nowhere in the application logs

  Scenario: An expired access token is rejected by protected endpoints
    Given the access token issued with "R1" is 16 minutes old
    When it is used to read the user's profile
    Then the response status is 401
    And the error code is "ACCESS_TOKEN_EXPIRED"

  @ui @issue-31
  Scenario: The web app refreshes transparently and handles revocation
    Given the web app has a valid refresh token and an expired access token
    When two API calls fail with 401 at the same time
    Then exactly one refresh request is sent
    And both calls are retried with the new access token
    When a later refresh returns "REFRESH_TOKEN_REUSED"
    Then the app clears local session state and redirects to sign-in with an explanation
