@USR-005 @issue-19 @issue-20 @api
Feature: Sign in and token validation
  Keycloak authenticates and issues tokens. The application validates every access token
  against the realm's keys and then authorizes from its own account state.

  Background:
    Given a Keycloak realm created from deploy/keycloak/realm-gavel.json
    And access tokens expire after 15 minutes and refresh tokens after 30 days

  Scenario: An active account signs in and reaches a protected endpoint
    Given "mina@example.com" is ACTIVE in the application
    When they sign in at Keycloak with the correct password
    Then Keycloak issues a JWT access token with claims "sub", "email", "email_verified" and audience "gavel-api"
    And a refresh token
    When the access token is used to read their own profile
    Then the response status is 200

  Scenario: Google sign-in for an active account
    Given "mina@example.com" is ACTIVE and linked to a Google identity
    When they sign in with Google
    Then Keycloak issues tokens for the same subject
    And reading their own profile returns their existing "userId"

  Scenario: A wrong password is rejected by the provider
    When someone signs in at Keycloak with "mina@example.com" and a wrong password
    Then Keycloak returns its invalid-credentials error
    And no token is issued

  Scenario Outline: Tokens that are not from this realm are rejected
    When a token <kind> is used to read a profile
    Then the response status is 401
    And the error code is "<code>"

    Examples:
      | kind                                         | code                 |
      | signed by a key the realm does not publish   | TOKEN_INVALID        |
      | with the right key but a different issuer    | TOKEN_INVALID        |
      | with audience other than "gavel-api"         | TOKEN_WRONG_AUDIENCE |
      | expired 16 minutes ago                       | TOKEN_EXPIRED        |

  Scenario Outline: A valid token does not override application account state
    Given "mina@example.com" holds a valid access token and is in application state "<state>"
    When they attempt to place a bid
    Then the response status is 403
    And the error code is "<code>"

    Examples:
      | state                      | code                               |
      | PENDING_VERIFICATION       | ACCOUNT_PENDING_VERIFICATION       |
      | PENDING_CREDIT_ELIGIBILITY | ACCOUNT_PENDING_CREDIT_ELIGIBILITY |
      | SUSPENDED                  | ACCOUNT_SUSPENDED                  |

  Scenario: A failed eligibility assessment is reported with the stable reason
    Given "mina@example.com" failed the assessment with reason "BELOW_MINIMUM_SCORE"
    When they call GET /api/v1/me
    Then the response status is 200
    And "accountState" is "PENDING_CREDIT_ELIGIBILITY" and "eligibilityReason" is "BELOW_MINIMUM_SCORE"

  Scenario: Repeated failed sign-ins are locked out by the provider
    When 10 sign-in attempts with a wrong password are made within one minute
    Then Keycloak's brute-force protection temporarily locks the account
    And Keycloak's event log records LOGIN_ERROR events with the lockout reason

  Scenario: JWKS rotation is handled without downtime
    Given the realm rotates its signing key
    When a token signed by the new key arrives
    Then the application refreshes its cached keys and accepts the token
    And tokens signed by the retired key are rejected once the realm stops publishing it

  @ui @issue-31
  Scenario: The web app maps provider and application errors to messages
    Given the web app is on the sign-in flow
    Then provider credential and lockout errors are shown on the themed Keycloak page
    And "ACCOUNT_PENDING_VERIFICATION" routes to the check-your-email page
    And "ACCOUNT_PENDING_CREDIT_ELIGIBILITY" routes to the eligibility check
    And "ACCOUNT_SUSPENDED" shows a support contact and signs the user out
