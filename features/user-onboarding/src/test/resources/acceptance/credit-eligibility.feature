@USR-011 @USR-012 @issue-17 @issue-18 @issue-23 @api
Feature: Credit-eligibility assessment
  After email verification a user supplies age, annual income and net wealth.
  The server scores them with a versioned policy; only a pass activates the account.
  Raw inputs are encrypted, never logged, never returned.

  Background:
    Given the configured scoring policy is version "test-policy-1" with a known threshold
    And under "test-policy-1" age 34, income 6800000 and wealth 1500000 meet the threshold
    And under "test-policy-1" age 19, income 900000 and wealth 0 do not meet the threshold
    And "mina@example.com" is in state "PENDING_CREDIT_ELIGIBILITY" and signed in

  Scenario: A passing assessment activates the account
    When they submit age 34, annual income 6800000 and net wealth 1500000 in minor units
    Then the response status is 200
    And the account state is "ACTIVE"
    And the stored assessment has outcome "ELIGIBLE", reason "MEETS_POLICY", policy version "test-policy-1" and an "assessedAt" timestamp
    And a "user-onboarding.credit-eligibility-assessed" event is written to the outbox
    And the event payload contains the outcome and policy version but not the inputs

  Scenario: A failing assessment leaves the account pending with a stable reason
    When they submit age 19, annual income 900000 and net wealth 0
    Then the response status is 422
    And the error code is "CREDIT_ELIGIBILITY_NOT_MET"
    And the error details include "eligibilityReason" = "BELOW_MINIMUM_SCORE"
    And the account state is still "PENDING_CREDIT_ELIGIBILITY"
    And the stored assessment has outcome "NOT_ELIGIBLE" and policy version "test-policy-1"

  @cross-feature
  Scenario: A failed account cannot bid or publish
    Given their assessment failed
    When they attempt to place a bid
    Then the response status is 403
    When they attempt to publish a listing
    Then the response status is 403

  Scenario Outline: Invalid inputs are rejected before scoring
    When they submit age <age>, annual income <income> and net wealth <wealth>
    Then the response status is 422
    And the error details name the field "<field>"
    And no assessment is recorded

    Examples:
      | age | income  | wealth  | field        |
      | 17  | 6800000 | 1500000 | age          |
      | 34  | -1      | 1500000 | annualIncome |
      | 34  | 6800000 | -250000 | netWealth    |
      | 34  | 68000.5 | 1500000 | annualIncome |

  Scenario: Assessment is only accepted after email verification
    Given "sam@example.com" is in state "PENDING_VERIFICATION"
    When "sam@example.com" submits assessment inputs
    Then the response status is 403
    And the error code is "ACCOUNT_PENDING_VERIFICATION"

  Scenario: Raw inputs are encrypted at rest
    When they submit a passing assessment
    Then the users row stores age, annual income and net wealth only in encrypted columns
    And the plaintext values do not appear in any database column or index

  Scenario: Raw inputs never appear in logs
    When they submit a passing assessment with income 6800000 and wealth 1500000
    Then the application logs for the request do not contain "6800000" or "1500000" or the age
    And the logs do contain the correlation id and the outcome

  Scenario: Raw inputs and the score are never returned by the API
    Given their assessment passed
    When they read their own profile
    Then the response contains "creditEligibilityOutcome", "eligibilityReason", "assessmentPolicyVersion" and "assessedAt"
    And the response contains no age, income, wealth or numeric score

  Scenario: The policy version in force at assessment time is recorded
    Given the configured policy is changed to version "test-policy-2" after an assessment under "test-policy-1"
    When they read their own profile
    Then "assessmentPolicyVersion" is still "test-policy-1"

  Scenario: A re-assessment is allowed only when the policy permits it
    Given their assessment failed under "test-policy-1" which allows a retry after 30 days
    When they submit new inputs the same day
    Then the response status is 409
    And the error code is "ASSESSMENT_RETRY_NOT_YET_ALLOWED"
    And the error details include the earliest retry time

  @ui @issue-32
  Scenario: The assessment form explains privacy and validates before submit
    Given the web app assessment page is open
    Then it states that inputs are encrypted and not shown again
    And money fields accept whole currency units and send integer minor units
    And a negative net wealth shows an inline error before any request is sent
