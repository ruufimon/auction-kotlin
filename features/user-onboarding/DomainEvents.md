# User Onboarding Feature - Domain Events

## Overview
The user-onboarding feature manages user registration, email verification, seller enablement, and account suspension. It publishes events that gate eligibility for bidding and selling, and integrates with other features for compliance.

## Events Published

### 1. USER_REGISTERED
**When:** New user account is created (email + password submitted).

**Data:**
```kotlin
UserRegisteredV1(
    userId: String,               // Unique user identifier
    email: String,                // Email address
    registeredAt: String,         // Timestamp
    accountType: String,          // BUYER | SELLER | BOTH
)
```

**Consumers:**
- **Bidding** (optional) — Track new user for analytics
- **User-Onboarding** — Trigger welcome email send
- **Audit Log** — Registration record

**Note:** At this point, user is NOT eligible to bid or sell. Must verify email first.

---

### 2. EMAIL_VERIFIED
**When:** User clicks email verification link and confirms ownership.

**Data:**
```kotlin
EmailVerifiedV1(
    userId: String,
    email: String,
    verifiedAt: String,
)
```

**Consumers:**
- **Bidding** — User can now place bids (requirement 5.1 USR-004)
- **Catalog** (optional) — User can browse listings  
- **Audit Log** — Verification record

**Check:** Bidding validates user is EMAIL_VERIFIED before accepting bids

---

### 3. SELLER_ENABLED
**When:** User completes seller profile (bank account, tax ID, business verification).

**Data:**
```kotlin
SellerEnabledV1(
    userId: String,
    sellerType: String,           // INDIVIDUAL | BUSINESS
    verifiedAt: String,
)
```

**Consumers:**
- **Catalog** — User can now create listings (requirement 5.2 CAT-002)
- **Audit Log** — Seller verification record

**Check:** Catalog validates user is SELLER_ENABLED before createListing()

---

### 4. ACCOUNT_SUSPENDED
**When:** User violates policy or is flagged for fraud/abuse.

**Data:**
```kotlin
AccountSuspendedV1(
    userId: String,
    reason: String,               // FRAUD | POLICY_VIOLATION | INACTIVITY | ADMIN_REQUEST
    suspendedAt: String,
)
```

**Consumers:**
- **Bidding** — Reject all bids from this user (requirement 5.1 USR-010)
- **Catalog** — Deactivate all listings by this user
- **Settlement** (optional) — Freeze seller payouts
- **Audit Log** — Suspension record with reason

**Effect:** Immediate lock on all user operations

---

### 5. ACCOUNT_REACTIVATED
**When:** Suspended account is cleared and user regains access.

**Data:**
```kotlin
AccountReactivatedV1(
    userId: String,
    reactivatedAt: String,
)
```

**Consumers:**
- **Bidding** — Accept bids again
- **Catalog** — Can create listings again
- **Audit Log** — Reactivation record

---

### 6. SELLER_DISABLED (optional)
**When:** Seller voluntarily disables selling capability while keeping buyer account.

**Data:**
```kotlin
SellerDisabledV1(
    userId: String,
    disabledAt: String,
)
```

**Consumers:**
- **Catalog** — Prevent createListing() calls
- **Audit Log** — Status change record

---

## Events Consumed

### None (User-Onboarding publishes only)
- User-onboarding is the source of truth for user state
- Other features consume events but don't publish back

---

## User State Machine

```
┌─────────────────────────────────────────┐
│  NO ACCOUNT                             │
│  (user visits site)                     │
└──────────────────┬──────────────────────┘
                   │ registerUser(email, password)
                   ↓
┌─────────────────────────────────────────┐
│  REGISTERED (not verified)              │ ← USER_REGISTERED event
│  ❌ Cannot bid                          │
│  ❌ Cannot sell                         │
│  Waiting for email verification         │
└──────────────────┬──────────────────────┘
                   │ clickVerificationLink()
                   ↓
┌─────────────────────────────────────────┐
│  EMAIL_VERIFIED                         │ ← EMAIL_VERIFIED event
│  ✓ Can bid                              │
│  ❌ Cannot sell (yet)                   │
│  (optional: create seller profile)      │
└──────────────────┬──────────────────────┘
                   │ completeSellerProfile(bankAccount, taxId)
                   ↓
┌─────────────────────────────────────────┐
│  SELLER_ENABLED                         │ ← SELLER_ENABLED event
│  ✓ Can bid                              │
│  ✓ Can sell                             │
│  (full feature access)                  │
└──────────────────┬──────────────────────┘
                   │ (anytime: suspended for policy violation)
                   ↓
┌─────────────────────────────────────────┐
│  ACCOUNT_SUSPENDED                      │ ← ACCOUNT_SUSPENDED event
│  ❌ Cannot bid                          │
│  ❌ Cannot sell                         │
│  ❌ Cannot withdraw funds               │
│  (all operations locked)                │
└──────────────────┬──────────────────────┘
                   │ adminReactivateAccount()
                   ↓
┌─────────────────────────────────────────┐
│  Restore to previous state              │ ← ACCOUNT_REACTIVATED event
│  (SELLER_ENABLED or EMAIL_VERIFIED)    │
└─────────────────────────────────────────┘
```

---

## Verification Workflows

### Email Verification
```kotlin
class EmailVerificationService(
    private val publisher: EventPublisher,
    private val auditLog: AuditLog
) {
    fun sendVerificationEmail(user: User) {
        val token = generateSecureToken()
        emailService.send(
            to = user.email,
            subject = "Verify your Gavel account",
            link = "https://gavel.app/verify?token=$token"
        )
        userRepository.saveVerificationToken(user.id, token)
    }
    
    fun verifyEmail(token: String): Result<Unit> {
        val userId = userRepository.getUserIdByVerificationToken(token)
            ?: return Result.failure(InvalidTokenException())
        
        val user = userRepository.findById(userId)
        user.emailVerifiedAt = Instant.now()
        userRepository.save(user)
        
        // Publish EMAIL_VERIFIED event
        publisher.publish(
            type = UserOnboardingEvents.EMAIL_VERIFIED,
            payloadVersion = 1,
            aggregateId = user.id,
            payload = json.encodeToString(EmailVerifiedV1(
                userId = user.id,
                email = user.email,
                verifiedAt = Instant.now().toString()
            ))
        )
        
        auditLog.record(
            actor = Actor.User(userId),
            action = "EMAIL_VERIFIED",
            entityType = "User",
            entityId = userId
        )
        
        return Result.success(Unit)
    }
}
```

### Seller Verification
```kotlin
class SellerVerificationService(
    private val publisher: EventPublisher,
    private val stripe: StripeClient,  // Payment processor
    private val taxVerification: TaxVerificationClient,
    private val auditLog: AuditLog
) {
    fun enableSeller(userId: String, sellerProfile: SellerProfile): Result<Unit> {
        // Step 1: Verify bank account
        val bankVerification = stripe.verifyBankAccount(
            accountNumber = sellerProfile.bankAccount,
            routingNumber = sellerProfile.routingNumber
        )
        if (!bankVerification.isValid) {
            return Result.failure(InvalidBankAccountException())
        }
        
        // Step 2: Verify tax ID
        val taxCheck = taxVerification.verify(
            taxId = sellerProfile.taxId,
            businessName = sellerProfile.businessName
        )
        if (!taxCheck.verified) {
            return Result.failure(InvalidTaxIdException())
        }
        
        // Step 3: Update user
        val user = userRepository.findById(userId)
        user.sellerProfile = sellerProfile
        user.sellerEnabledAt = Instant.now()
        userRepository.save(user)
        
        // Step 4: Publish SELLER_ENABLED event
        publisher.publish(
            type = UserOnboardingEvents.SELLER_ENABLED,
            payloadVersion = 1,
            aggregateId = user.id,
            payload = json.encodeToString(SellerEnabledV1(
                userId = user.id,
                sellerType = sellerProfile.type.name,
                verifiedAt = Instant.now().toString()
            ))
        )
        
        auditLog.record(
            actor = Actor.User(userId),
            action = "SELLER_ENABLED",
            entityType = "User",
            entityId = userId,
            details = mapOf(
                "sellerType" to sellerProfile.type.name,
                "bankVerified" to "true"
            )
        )
        
        return Result.success(Unit)
    }
}
```

### Suspension Workflow
```kotlin
class AccountSuspensionService(
    private val publisher: EventPublisher,
    private val auditLog: AuditLog
) {
    fun suspendAccount(userId: String, reason: String, adminId: String) {
        val user = userRepository.findById(userId)
        user.status = UserStatus.SUSPENDED
        user.suspendedAt = Instant.now()
        user.suspensionReason = reason
        userRepository.save(user)
        
        // Publish ACCOUNT_SUSPENDED event
        publisher.publish(
            type = UserOnboardingEvents.ACCOUNT_SUSPENDED,
            payloadVersion = 1,
            aggregateId = userId,
            payload = json.encodeToString(AccountSuspendedV1(
                userId = userId,
                reason = reason,
                suspendedAt = Instant.now().toString()
            ))
        )
        
        auditLog.record(
            actor = Actor.Admin(adminId),
            action = "ACCOUNT_SUSPENDED",
            entityType = "User",
            entityId = userId,
            details = mapOf("reason" to reason)
        )
    }
    
    fun reactivateAccount(userId: String, adminId: String) {
        val user = userRepository.findById(userId)
        val previousStatus = user.status  // Could be EMAIL_VERIFIED or SELLER_ENABLED
        user.status = previousStatus     // Restore previous state
        user.suspendedAt = null
        user.suspensionReason = null
        userRepository.save(user)
        
        // Publish ACCOUNT_REACTIVATED event
        publisher.publish(
            type = UserOnboardingEvents.ACCOUNT_REACTIVATED,
            payloadVersion = 1,
            aggregateId = userId,
            payload = json.encodeToString(AccountReactivatedV1(
                userId = userId,
                reactivatedAt = Instant.now().toString()
            ))
        )
        
        auditLog.record(
            actor = Actor.Admin(adminId),
            action = "ACCOUNT_REACTIVATED",
            entityType = "User",
            entityId = userId
        )
    }
}
```

---

## Cross-Feature Eligibility Checks

### Bidding Checks
```kotlin
class BidAcceptanceValidator(
    private val userService: UserService,
    private val auctionService: AuctionService
) {
    fun validateBidder(userId: String, auctionId: String): Result<Unit> {
        // Check 1: User exists and email verified
        val user = userService.findById(userId)
            ?: return Result.failure(UserNotFoundException())
        
        if (!user.emailVerified) {
            return Result.failure(EmailNotVerifiedException())
        }
        
        // Check 2: Account not suspended
        if (user.status == UserStatus.SUSPENDED) {
            return Result.failure(AccountSuspendedException())
        }
        
        // Check 3: Auction is active
        val auction = auctionService.findById(auctionId)
            ?: return Result.failure(AuctionNotFoundException())
        
        if (auction.status != AuctionStatus.ACTIVE) {
            return Result.failure(AuctionNotActiveException())
        }
        
        // Check 4: Seller cannot bid on own auction
        if (auction.sellerId == userId) {
            return Result.failure(SellerCannotBidException())
        }
        
        return Result.success(Unit)
    }
}
```

### Catalog Checks
```kotlin
class ListingCreationValidator(
    private val userService: UserService
) {
    fun validateSeller(userId: String): Result<Unit> {
        val user = userService.findById(userId)
            ?: return Result.failure(UserNotFoundException())
        
        // Check 1: Email verified
        if (!user.emailVerified) {
            return Result.failure(EmailNotVerifiedException())
        }
        
        // Check 2: Seller enabled
        if (user.status != UserStatus.SELLER_ENABLED) {
            return Result.failure(SellerNotEnabledException())
        }
        
        // Check 3: Account not suspended
        if (user.suspendedAt != null) {
            return Result.failure(AccountSuspendedException())
        }
        
        return Result.success(Unit)
    }
}
```

---

## Compliance & Auditability

### Requirement 2.2: Auditability
- Every state transition is an event
- Every event is audited with actor and timestamp
- Immutable log of user lifecycle

### Requirement 5.1: USR-001 through USR-010
- **USR-001**: User registration via email ✓ (USER_REGISTERED)
- **USR-002**: Password requirements ✓ (enforced in registerUser)
- **USR-003**: Email verification required ✓ (EMAIL_VERIFIED gate)
- **USR-004**: Email-verified users can bid ✓ (BID_ACCEPTED validator checks)
- **USR-005**: Two-factor auth (optional)
- **USR-006**: Seller profile completion ✓ (SELLER_ENABLED event)
- **USR-007**: Tax/Bank verification ✓ (SellerVerificationService)
- **USR-008**: Personal data collection ✓ (AuditLog records)
- **USR-009**: Account settings ✓ (user preferences table)
- **USR-010**: Suspension mechanism ✓ (ACCOUNT_SUSPENDED event)

---

## Testing

### Unit Tests
- [x] User registration flow (USER_REGISTERED event)
- [x] Email verification (EMAIL_VERIFIED event)
- [x] Seller profile completion (SELLER_ENABLED event)
- [x] Account suspension (ACCOUNT_SUSPENDED event)
- [x] Account reactivation (ACCOUNT_REACTIVATED event)
- [x] Email already verified → already-enabled users
- [x] Seller already enabled → reactivation restores SELLER_ENABLED

### Integration Tests
- [x] Complete user flow: register → verify email → enable seller → create listing
- [x] Suspension flow: user tries to bid → account suspended → bids rejected → reactivated → can bid again
- [x] Suspension cascades: user suspended → all listings deactivated → new bids rejected
- [x] Event idempotency: EMAIL_VERIFIED replayed → no duplicate state change

### Event Flow Tests
- [x] USER_REGISTERED fires on new account
- [x] EMAIL_VERIFIED fires on verification link click
- [x] SELLER_ENABLED fires on profile completion
- [x] ACCOUNT_SUSPENDED fires on admin suspension
- [x] ACCOUNT_REACTIVATED restores previous state

---

## See Also
- **DOMAIN_EVENTS.md** — Core event architecture and patterns
- **requirement.md** — Section 5.1 (User onboarding requirements)
- **RESTRUCTURING.md** — Feature organization
