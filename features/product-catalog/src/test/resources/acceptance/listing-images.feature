@CAT-002 @AFA-37 @AFA-92 @api @wip
Feature: Listing images
  Sellers upload images straight to object storage with a pre-signed URL, then confirm the
  upload. JPEG, PNG or WebP only, at most 5 MB each and 10 per listing. Tests run against
  MinIO, never DigitalOcean Spaces.

  Background:
    Given "mina@example.com" is ACTIVE with selling enabled and has a DRAFT listing

  Scenario: A seller gets a pre-signed upload URL
    When "mina@example.com" requests an image upload for "image/jpeg" of 2000000 bytes
    Then the response status is 201
    And the response has an "imageId", an "uploadUrl" and an "expiresAt" 10 minutes ahead
    And the image is recorded as "pending"

  Scenario: Upload, confirm, and the image is attached
    Given "mina@example.com" requested an image upload for "image/png" of 300000 bytes
    And the file was uploaded to the pre-signed URL
    When they confirm the image
    Then the response status is 200
    And the image is "attached" and listed on the draft with a display URL

  Scenario Outline: Disallowed uploads are refused before any URL is issued
    When "mina@example.com" requests an image upload for "<type>" of <bytes> bytes
    Then the response status is 422
    And the error code is "<code>"

    Examples:
      | type            | bytes   | code                    |
      | image/gif       | 100000  | IMAGE_TYPE_NOT_ALLOWED  |
      | application/pdf | 100000  | IMAGE_TYPE_NOT_ALLOWED  |
      | image/jpeg      | 5242881 | IMAGE_TOO_LARGE         |
      | image/jpeg      | 0       | VALIDATION_FAILED       |

  Scenario: An eleventh image is refused
    Given the draft has 10 attached images
    When "mina@example.com" requests another image upload
    Then the response status is 422
    And the error code is "TOO_MANY_IMAGES"

  Scenario: The pre-signed URL only accepts the declared file
    Given "mina@example.com" requested an image upload for "image/jpeg" of 200000 bytes
    When a file of type "image/png" or a different size is sent to the upload URL
    Then the storage rejects the upload

  Scenario: Confirming without an uploaded file fails
    Given "mina@example.com" requested an image upload but uploaded nothing
    When they confirm the image
    Then the response status is 422
    And the error code is "IMAGE_NOT_UPLOADED"
    And the image is still "pending"

  Scenario: An uploaded object that does not match what was declared is rejected
    Given "mina@example.com" requested an image upload for "image/jpeg" of 200000 bytes
    And an object of 900000 bytes ended up at that key
    When they confirm the image
    Then the response status is 422
    And the error code is "IMAGE_MISMATCH"
    And the object is deleted

  Scenario: Images can be reordered and removed
    Given the draft has attached images A, B and C in that order
    When "mina@example.com" orders them C, A, B
    Then the draft lists the images C, A, B
    When they remove image A
    Then the draft lists the images C, B
    And the object for image A is deleted from storage

  Scenario: Another user cannot add or remove images
    Given "sam@example.com" is ACTIVE with selling enabled
    When "sam@example.com" requests an image upload on "mina@example.com"'s draft
    Then the response status is 403

  Scenario Outline: Images can change only while the listing is editable
    Given "mina@example.com" has a <state> listing
    When they request an image upload on it
    Then the response status is <status>

    Examples:
      | state     | status |
      | SCHEDULED | 201    |
      | SOLD      | 409    |
      | UNSOLD    | 409    |
      | CANCELLED | 409    |

  Scenario: Pending uploads that are never confirmed are cleaned up
    Given "mina@example.com" requested an image upload 25 hours ago and never confirmed it
    When the storage lifecycle rule has run
    Then the pending object no longer exists

  Scenario: Images of a cancelled listing are removed
    Given "mina@example.com" has a SCHEDULED listing with 2 attached images
    When they cancel it
    And the image cleanup job runs
    Then both image objects are deleted from storage

  Scenario: Storage credentials never reach the client
    When "mina@example.com" requests an image upload
    Then the response contains no access key or secret
