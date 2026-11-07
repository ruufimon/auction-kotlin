@issue-19 @issue-21 @api
Feature: Abuse controls on onboarding
  requirement.md §8.2: login, refresh and verification abuse controls come from Keycloak's
  brute-force protection in the versioned realm; the account bootstrap endpoint keeps an
  application-side rate limit.

  Scenario: Brute-force protection is enabled in the versioned realm
    Given a Keycloak realm created from deploy/keycloak/realm-gavel.json
    Then brute-force detection is enabled with a bounded failure count and a wait increment
    And 10 failed sign-ins within one minute lock the account temporarily

  Scenario: Verification resend is throttled by the provider
    Given "mina@example.com" requested a verification resend 30 seconds ago
    When they request another resend
    Then Keycloak refuses until its action-token resend interval has passed

  Scenario: Account bootstrap is rate limited in the application
    When 60 GET /api/v1/me calls are made from one client within one minute
    Then the next call returns status 429
    And the error code is "RATE_LIMITED"
    And a "Retry-After" header is present

  Scenario: Throttling does not leak account existence
    When the lockout response for a registered email is compared with one for an unregistered email
    Then the status and body shape are identical

  Scenario: Abuse events are observable
    When a client is locked out or throttled
    Then Keycloak's LOGIN_ERROR events and the app's ratelimit counter are visible in SigNoz
    And no log line carries credentials
