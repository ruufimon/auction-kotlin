@USR-003 @USR-004 @USR-013 @issue-19 @issue-21 @api
Feature: Email verification
  Keycloak sends and validates the verification link. The application learns the result
  from the email_verified claim and moves the account to PENDING_CREDIT_ELIGIBILITY
  without activating it.

  Background:
    Given a Keycloak realm created from deploy/keycloak/realm-gavel.json with verify-email required
    And "mina@example.com" registered with a password at "2026-11-07T10:00:00Z" and received a verification email

  Scenario: A valid link verifies the email at the provider and advances the account on the next request
    When the verification link is opened at "2026-11-07T10:30:00Z"
    And the user signs in again and the web app calls GET /api/v1/me
    Then the token claim "email_verified" is true
    And the response "accountState" is "PENDING_CREDIT_ELIGIBILITY"
    And an "user-onboarding.email-verified" event is written to the outbox

  @cross-feature
  Scenario: Verification alone does not activate the account
    Given the email has been verified
    When that user attempts to place a bid on an active auction
    Then the response status is 403
    And the error code is "ACCOUNT_PENDING_CREDIT_ELIGIBILITY"
    When that user attempts to publish a listing
    Then the response status is 403

  @cross-feature
  Scenario: A valid token whose email_verified claim is false is refused for bidding
    Given a valid access token for "mina@example.com" with "email_verified" false
    When it is used to place a bid
    Then the response status is 403
    And the error code is "ACCOUNT_PENDING_VERIFICATION"

  @provider
  Scenario: A verification link cannot be used twice (provider)
    Given the link has been used successfully
    When the same link is opened again
    Then Keycloak shows its already-verified / link-no-longer-valid page
    And the account state is still "PENDING_CREDIT_ELIGIBILITY"

  @provider
  Scenario: An expired link is rejected (provider)
    Given the realm action-token lifespan is 24 hours
    When the link is opened at "2026-11-08T10:00:01Z"
    Then Keycloak shows its link-expired page with a resend action
    And the account state is still "PENDING_VERIFICATION"

  @provider
  Scenario: Resend issues a new link and invalidates the previous one (provider)
    When the user requests a new verification email
    Then a second verification email is sent
    And the first link is rejected
    And the second link verifies the email

  Scenario: Verification is recorded for audit
    When the email is verified and the account transitions
    Then the audit log holds an entry with actor = the user, action = "email.verified" and the aggregate id

  @provider
  Scenario: Verification is recorded at the provider
    When the verification link is opened
    Then Keycloak's event log holds a VERIFY_EMAIL event for the user

  @ui @issue-30
  Scenario: Post-login routing by account state
    Given the web app receives tokens after login
    When GET /api/v1/me returns "PENDING_VERIFICATION"
    Then the app shows the "check your email" page with a resend action
    When GET /api/v1/me returns "PENDING_CREDIT_ELIGIBILITY"
    Then the app routes to the eligibility assessment
