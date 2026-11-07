@USR-001 @USR-002 @USR-003 @USR-009 @issue-19 @api
Feature: Registration
  A visitor creates one account that can later both buy and sell.
  Registration creates a PENDING_VERIFICATION account and never grants bidding.

  Background:
    Given the password policy requires at least 12 characters
    And no account exists for "mina@example.com"

  Scenario: Successful registration creates a pending account
    When a visitor registers with email "mina@example.com", display name "Mina K." and password "correct-horse-battery"
    Then the response status is 201
    And the response contains an opaque "userId" that is not a sequential integer
    And the response "accountState" is "PENDING_VERIFICATION"
    And the response does not contain the password or its hash
    And a "user-onboarding.user-registered" event is written to the outbox in the same transaction

  Scenario: Registration sends a single-use verification token
    When a visitor registers with email "mina@example.com" and a valid password
    Then exactly one verification email is sent to "mina@example.com"
    And the token in that email is stored only as a hash
    And the token has an expiry in the future

  Scenario: Email addresses are normalized before uniqueness is checked
    Given an account exists for "mina@example.com"
    When a visitor registers with email "  Mina@Example.COM " and a valid password
    Then the response status is 409
    And the error code is "EMAIL_ALREADY_REGISTERED"
    And no second account is created

  Scenario Outline: Weak passwords are rejected by the configured policy
    When a visitor registers with email "mina@example.com" and password "<password>"
    Then the response status is 400
    And the error code is "PASSWORD_POLICY_VIOLATION"
    And the error details name the field "password"
    And no account is created

    Examples:
      | password     |
      | short1       |
      | elevenchars1 |
      |              |

  Scenario Outline: Malformed email is rejected
    When a visitor registers with email "<email>" and a valid password
    Then the response status is 400
    And the error details name the field "email"

    Examples:
      | email        |
      | not-an-email |
      | @example.com |
      |              |

  Scenario: Passwords are stored only as memory-hard hashes
    When a visitor registers with email "mina@example.com" and password "correct-horse-battery"
    Then the stored password hash is produced by a memory-hard algorithm
    And the plaintext password appears nowhere in the database or the application logs

  Scenario: A pending account cannot bid
    Given "mina@example.com" registered and has not verified their email
    When that user attempts to place a bid on an active auction
    Then the response status is 403
    And the error code is "ACCOUNT_NOT_ACTIVE"

  Scenario: One account serves both buyer and seller roles
    When a visitor registers with email "mina@example.com" and a valid password
    Then the registration form and API accept no role or account-type choice
    And the created account has "sellerEnabled" false

  @ui @issue-30
  Scenario: Registration form shows server errors inline
    Given the web app registration page is open
    When the visitor submits an email that is already registered
    Then the email field shows the error mapped from "EMAIL_ALREADY_REGISTERED"
    And a link to the sign-in page is offered
    And the entered display name and password are not lost
