@gap @api
Feature: Abuse controls on onboarding endpoints
  requirement.md §8.2: login, token refresh and verification endpoints require
  rate limiting or equivalent abuse controls. No M2 issue implements this yet;
  these scenarios define the expected behaviour so an issue can be filed against them.

  Scenario Outline: Repeated failures from one source are throttled
    When <attempts> failed requests are made to "<endpoint>" from one client within one minute
    Then the next request returns status 429
    And the error code is "RATE_LIMITED"
    And a "Retry-After" header is present

    Examples:
      | endpoint              | attempts |
      | /api/v1/auth/signin   | 10       |
      | /api/v1/auth/refresh  | 20       |
      | /api/v1/auth/verify-email | 10   |

  Scenario: Verification resend is throttled per account
    Given "mina@example.com" requested a verification resend 30 seconds ago
    When they request another resend
    Then the response status is 429
    And the error details include the earliest time a resend is allowed

  Scenario: Throttling does not leak account existence
    When the throttled response for a registered email is compared with one for an unregistered email
    Then the status, code and body shape are identical

  Scenario: Throttling events are observable
    When a client is throttled
    Then a metric counter for the endpoint is incremented
    And the log line carries the correlation id but not the credentials
