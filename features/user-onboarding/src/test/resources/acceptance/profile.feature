@wip
@USR-007 @issue-23 @api
Feature: Profile
  A user reads and updates their own display name, contact details and
  shipping address, and only their own.

  Background:
    Given "mina@example.com" is ACTIVE and signed in with user id "usr_mina"
    And "sam@example.com" is ACTIVE with user id "usr_sam"

  Scenario: Reading your own profile
    When "usr_mina" reads profile "usr_mina"
    Then the response status is 200
    And the response contains userId, email, displayName, accountState, emailVerified, sellerEnabled, createdAt
    And the response contains creditEligibilityOutcome, eligibilityReason, assessmentPolicyVersion, assessedAt
    And the response contains no password hash, token, age, income, wealth or score

  Scenario: Reading another user's profile is forbidden
    When "usr_mina" reads profile "usr_sam"
    Then the response status is 403
    And the error code is "FORBIDDEN"

  Scenario: Reading a profile that does not exist
    When "usr_mina" reads profile "usr_nobody"
    Then the response status is 403
    And the response is indistinguishable from reading another user's profile

  Scenario: Updating display name and contact details
    When "usr_mina" updates their profile with display name "Mina Kessler" and phone "+15035550142"
    Then the response status is 200
    And the response reflects the new values and a new "version"
    And a "user-onboarding.profile-updated" event is written to the outbox

  Scenario: Updating the shipping address
    When "usr_mina" updates their shipping address to "418 NW Couch St", "Portland", "OR", "97209", "US"
    Then the response status is 200
    And reading the profile returns that address

  Scenario: Updating another user's profile is forbidden
    When "usr_mina" updates profile "usr_sam"
    Then the response status is 403
    And "usr_sam" is unchanged

  Scenario Outline: Invalid profile values are rejected with field details
    When "usr_mina" updates their profile with "<field>" = "<value>"
    Then the response status is 422
    And the error details name the field "<field>"

    Examples:
      | field       | value                                  |
      | displayName |                                        |
      | displayName | <61 characters>                        |
      | phone       | not-a-phone                            |
      | postalCode  |                                        |

  Scenario: Concurrent edits are detected
    Given "usr_mina" read their profile at version 3
    And another session has since saved version 4
    When "usr_mina" submits an update based on version 3
    Then the response status is 409
    And the error code is "VERSION_CONFLICT"

  Scenario: Email cannot be changed through the profile endpoint
    When "usr_mina" updates their profile with email "other@example.com"
    Then the response status is 422
    And the stored email is still "mina@example.com"

  Scenario: Unauthenticated access is rejected
    When an anonymous client reads profile "usr_mina"
    Then the response status is 401

  @ui @issue-32
  Scenario: The profile page shows the account status banner for every state
    Given the web app profile page is open
    Then a PENDING_VERIFICATION account sees a resend-email action
    And a PENDING_CREDIT_ELIGIBILITY account sees a link to the eligibility check
    And a failed assessment shows the stable reason code and any retry date
    And an ACTIVE account sees no blocking banner
    And a SUSPENDED account sees a support contact and no editable actions
