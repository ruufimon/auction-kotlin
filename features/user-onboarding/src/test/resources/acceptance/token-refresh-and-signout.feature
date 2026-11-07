@USR-005 @USR-006 @issue-19 @issue-31 @api
Feature: Token refresh and sign-out
  Refresh, rotation, reuse detection and sign-out are provider behaviours configured in the
  versioned realm; the application never stores a refresh token.

  Background:
    Given a Keycloak realm created from deploy/keycloak/realm-gavel.json with "Revoke Refresh Token" on and max reuse 0
    And "mina@example.com" is ACTIVE and signed in, holding refresh token "R1"

  @provider
  Scenario: Refresh rotates the token
    When "R1" is presented to Keycloak's token endpoint
    Then a new access token and a new refresh token "R2" are issued
    And Keycloak's event log records REFRESH_TOKEN

  @provider
  Scenario: A rotated token cannot be reused, and reuse ends the session
    Given "R1" was rotated into "R2"
    When "R1" is presented again
    Then Keycloak returns an invalid_grant error
    And the session is ended so "R2" is also rejected
    And Keycloak's event log records REFRESH_TOKEN_ERROR

  @provider
  Scenario: An expired refresh token is rejected
    Given "R1" is older than the refresh lifespan
    When "R1" is presented
    Then Keycloak returns an invalid_grant error

  @provider
  Scenario: Sign-out ends the session
    When the web app calls Keycloak's end-session endpoint with the session's id token
    Then "R1" is rejected on the next refresh
    And Keycloak's event log records LOGOUT

  @provider
  Scenario: Sign-out everywhere
    Given the user also has a session on another device
    When the user chooses "sign out all devices" (Keycloak account console or admin logout)
    Then both sessions' refresh tokens are rejected

  Scenario: The application holds no refresh tokens
    Then the application database has no table or column containing refresh tokens
    And the application logs contain no refresh-token values

  Scenario: An expired access token is rejected by protected endpoints
    Given the access token is 16 minutes old
    When it is used to read the user's profile
    Then the response status is 401
    And the error code is "TOKEN_EXPIRED"

  @ui @issue-31
  Scenario: The web app refreshes transparently and handles revocation
    Given the web app has a valid refresh token and an expired access token
    When two API calls fail with 401 TOKEN_EXPIRED at the same time
    Then exactly one refresh request is sent to Keycloak
    And both calls are retried with the new access token
    When a later refresh fails with invalid_grant
    Then the app clears local session state and redirects to sign-in with an explanation
