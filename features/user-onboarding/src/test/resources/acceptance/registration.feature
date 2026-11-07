@USR-001 @USR-002 @USR-003 @USR-009 @USR-013 @issue-19 @issue-21 @api
Feature: Registration and first sign-in
  Identity lives in Keycloak (Google sign-in or email and password). The application
  creates its own account record on the first authenticated request and never sees a password.

  Background:
    Given a Keycloak realm created from deploy/keycloak/realm-gavel.json
    And the realm password policy requires at least 12 characters
    And no application account exists for "mina@example.com"

  Scenario: Email-and-password registration at the provider, then bootstrap
    When a visitor registers at Keycloak with email "mina@example.com" and password "correct-horse-battery"
    And the web app calls GET /api/v1/me with the resulting access token
    Then the response status is 200
    And the response contains an opaque "userId" that is not a sequential integer
    And the response "accountState" is "PENDING_VERIFICATION"
    And the response "emailVerified" is false
    And an "user-onboarding.account-created" event is written to the outbox in the same transaction

  Scenario: Google sign-in with a verified email skips verification
    When a visitor signs in with Google as "mina@example.com" (verified email)
    And the web app calls GET /api/v1/me with the resulting access token
    Then the response status is 200
    And the response "accountState" is "PENDING_CREDIT_ELIGIBILITY"
    And the response "emailVerified" is true

  Scenario: A Google identity links to an existing account with the same verified email
    Given "mina@example.com" registered with a password and verified their email, with userId "usr_mina"
    When the same person signs in with Google as "mina@example.com"
    And the web app calls GET /api/v1/me
    Then the response "userId" is "usr_mina"
    And exactly one application account exists for "mina@example.com"
    And exactly one Keycloak user exists for "mina@example.com"

  Scenario: Bootstrap is idempotent under concurrent first calls
    Given a fresh Keycloak user with no application account
    When two GET /api/v1/me calls arrive at the same time with that user's token
    Then both return 200 with the same "userId"
    And exactly one account row exists for the subject

  Scenario: Provider rejects duplicate emails
    Given a Keycloak user exists for "mina@example.com"
    When a visitor registers at Keycloak with "  Mina@Example.COM " and a valid password
    Then Keycloak refuses with its duplicate-email error
    And no second Keycloak user or application account is created

  Scenario Outline: Provider rejects weak passwords
    When a visitor registers at Keycloak with email "mina@example.com" and password "<password>"
    Then Keycloak refuses with its password-policy error

    Examples:
      | password     |
      | short1       |
      | elevenchars1 |

  Scenario: The application never stores or logs passwords
    Then the application schema has no password column and no refresh-token table
    And the application logs for a registration contain no password value

  Scenario: A pending account cannot bid
    Given "mina@example.com" bootstrapped in "PENDING_VERIFICATION"
    When that user attempts to place a bid on an active auction
    Then the response status is 403
    And the error code is "ACCOUNT_PENDING_VERIFICATION"

  Scenario: One account serves both buyer and seller roles
    When a visitor registers and bootstraps
    Then no role or account-type choice was offered
    And the created account has "sellerEnabled" false

  @ui @issue-30
  Scenario: Keycloak registration page offers Google and email with inline errors
    Given the themed Keycloak registration page is open
    Then it shows a "Continue with Google" button and an email/password form
    When the visitor submits an email that is already registered
    Then the email field shows the duplicate-email error
    And a link to sign in is offered
