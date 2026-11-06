package com.gavel.onboarding.application

import com.gavel.kernel.Actor
import com.gavel.kernel.AuditLog
import com.gavel.kernel.DomainException
import com.gavel.kernel.ErrorKind
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.Secrets
import com.gavel.kernel.Transactor
import com.gavel.kernel.UserId
import com.gavel.kernel.Validation
import com.gavel.kernel.conflict
import com.gavel.kernel.notFound
import com.gavel.onboarding.api.AccountStatus
import com.gavel.onboarding.api.OnboardingEvents
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.AccountRules
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.PasswordPolicy
import com.gavel.onboarding.domain.Preferences
import com.gavel.onboarding.domain.SellerProfile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class OnboardingSettings(
    val passwordPolicy: PasswordPolicy = PasswordPolicy(),
    val verificationTokenTtl: Duration = Duration.ofHours(24),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    /** Base URL of the web page that completes verification; the token is appended as ?token=. */
    val verificationLinkBase: String = "http://localhost:3000/verify-email",
)

data class RegisterCommand(
    val email: String,
    val password: String,
    val displayName: String,
    val phone: String?,
    val acceptedTerms: Boolean,
)

data class ProfileUpdate(
    val displayName: String? = null,
    val phone: String? = null,
    val shippingAddress: Address? = null,
    val preferences: Preferences? = null,
)

/** A signed-in session: a short-lived access token plus a rotating refresh token. */
data class Session(val accessToken: String, val expiresInSeconds: Long, val refreshToken: String, val refreshExpiresAt: Instant, val account: Account)

class OnboardingService(
    private val tx: Transactor,
    private val accounts: AccountRepository,
    private val hasher: PasswordHasher,
    private val tokens: AccessTokens,
    private val email: EmailSender,
    private val audit: AuditLog,
    private val events: EventPublisher,
    private val clock: Clock,
    private val settings: OnboardingSettings = OnboardingSettings(),
) {
    // Spends the same hashing effort for unknown emails, so sign-in timing does not reveal accounts.
    private val dummyHash by lazy { hasher.hash(Secrets.randomToken()) }

    suspend fun register(cmd: RegisterCommand): Account {
        val email = AccountRules.normalizeEmail(cmd.email)
        val v = Validation()
        AccountRules.validateEmail(email, v)
        AccountRules.validateDisplayName(cmd.displayName, v)
        cmd.phone?.let { AccountRules.validatePhone(it, v) }
        settings.passwordPolicy.violations(cmd.password).forEach(v::add)
        v.check(cmd.acceptedTerms, "acceptedTerms", "REQUIRED", "Accept the marketplace terms to continue.")
        v.throwIfInvalid()

        val passwordHash = hasher.hash(cmd.password)
        val now = clock.instant()
        val token = Secrets.randomToken()
        val account = Account(
            id = UserId.new(), email = email, passwordHash = passwordHash,
            status = AccountStatus.PENDING_VERIFICATION, emailVerifiedAt = null,
            displayName = cmd.displayName.trim(), phone = cmd.phone?.trim(), shippingAddress = null,
            preferences = Preferences(), sellerProfile = null, termsAcceptedAt = now, createdAt = now, version = 1,
        )
        tx.inTransaction {
            if (accounts.findByEmail(email) != null) conflict("EMAIL_TAKEN", "An account already uses this email address.")
            accounts.insert(account)
            accounts.insertVerificationToken(account.id, Secrets.sha256Hex(token), now + settings.verificationTokenTtl, now)
            audit.record(Actor.User(account.id), "account.registered", "account", account.id.toString())
        }
        sendVerification(account, token)
        return account
    }

    /**
     * Creates an already-verified account without sending email. For demo data
     * and operator tooling only; never exposed over HTTP. Returns null if the email exists.
     */
    suspend fun provisionVerified(cmd: RegisterCommand, sellerProfile: SellerProfile?): Account? {
        val email = AccountRules.normalizeEmail(cmd.email)
        val now = clock.instant()
        val account = Account(
            id = UserId.new(), email = email, passwordHash = hasher.hash(cmd.password),
            status = AccountStatus.ACTIVE, emailVerifiedAt = now,
            displayName = cmd.displayName.trim(), phone = cmd.phone?.trim(), shippingAddress = null,
            preferences = Preferences(), sellerProfile = sellerProfile, termsAcceptedAt = now, createdAt = now, version = 1,
        )
        return tx.inTransaction {
            if (accounts.findByEmail(email) != null) return@inTransaction null
            accounts.insert(account)
            audit.record(Actor.System("provisioning"), "account.provisioned", "account", account.id.toString())
            account
        }
    }

    /** Always succeeds so callers cannot probe which emails are registered. */
    suspend fun resendVerification(rawEmail: String) {
        val now = clock.instant()
        val token = Secrets.randomToken()
        val account = tx.inTransaction {
            val account = accounts.findByEmail(AccountRules.normalizeEmail(rawEmail))
                ?.takeIf { !it.emailVerified && it.status == AccountStatus.PENDING_VERIFICATION }
                ?: return@inTransaction null
            accounts.discardUnusedVerificationTokens(account.id)
            accounts.insertVerificationToken(account.id, Secrets.sha256Hex(token), now + settings.verificationTokenTtl, now)
            account
        }
        account?.let { sendVerification(it, token) }
    }

    /** USR-003: tokens are single-use and expire. Verification signs the user in. */
    suspend fun verifyEmail(token: String): Session {
        val now = clock.instant()
        val session = tx.inTransaction {
            val userId = accounts.consumeVerificationToken(Secrets.sha256Hex(token), now)
                ?: throw DomainException(ErrorKind.RULE_VIOLATION, "VERIFICATION_TOKEN_INVALID", "This verification link is invalid, used or expired.")
            val account = accounts.findById(userId, forUpdate = true) ?: notFound("Account")
            var current = account
            if (!account.emailVerified) {
                current = account.verifyEmail(now)
                saveOrConflict(current, account.version)
                current = current.copy(version = account.version + 1)
                audit.record(Actor.User(userId), "account.email-verified", "account", userId.toString())
            }
            // A suspended account keeps its verification but gets no session.
            if (current.status == AccountStatus.ACTIVE) newSession(current, now, UUID.randomUUID()) else null
        }
        return session ?: suspended()
    }

    suspend fun signIn(rawEmail: String, password: String): Session {
        val now = clock.instant()
        val account = tx.inTransaction { accounts.findByEmail(AccountRules.normalizeEmail(rawEmail)) }
        val valid = hasher.verify(password, account?.passwordHash ?: dummyHash) && account != null
        if (!valid || account == null) {
            throw DomainException(ErrorKind.UNAUTHENTICATED, "INVALID_CREDENTIALS", "The email or password is incorrect.")
        }
        when (account.status) {
            AccountStatus.PENDING_VERIFICATION -> throw DomainException(ErrorKind.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email address before signing in.")
            AccountStatus.SUSPENDED -> suspended()
            AccountStatus.ACTIVE -> Unit
        }
        return tx.inTransaction { newSession(account, now, UUID.randomUUID()) }
    }

    /** USR-006: rotates the refresh token; reusing a rotated token revokes the whole family. */
    suspend fun refresh(rawToken: String): Session {
        val now = clock.instant()
        val outcome: Result<Session> = tx.inTransaction {
            val stored = accounts.findRefreshTokenForUpdate(Secrets.sha256Hex(rawToken))
                ?: return@inTransaction Result.failure(invalidRefresh())
            if (stored.rotatedAt != null || stored.revokedAt != null) {
                if (stored.revokedAt == null || stored.rotatedAt != null) {
                    accounts.revokeFamily(stored.familyId, now)
                    audit.record(Actor.User(stored.userId), "auth.refresh-token-reused", "account", stored.userId.toString(), mapOf("familyId" to stored.familyId.toString()))
                }
                return@inTransaction Result.failure(invalidRefresh())
            }
            if (!stored.expiresAt.isAfter(now)) return@inTransaction Result.failure(invalidRefresh())
            val account = accounts.findById(stored.userId)
            if (account == null || account.status != AccountStatus.ACTIVE) {
                accounts.revokeFamily(stored.familyId, now)
                return@inTransaction Result.failure(invalidRefresh())
            }
            accounts.markRotated(stored.id, now)
            Result.success(newSession(account, now, stored.familyId))
        }
        return outcome.getOrThrow()
    }

    suspend fun signOut(rawToken: String?) {
        if (rawToken.isNullOrBlank()) return
        val now = clock.instant()
        tx.inTransaction {
            accounts.findRefreshTokenForUpdate(Secrets.sha256Hex(rawToken))?.let { accounts.revokeFamily(it.familyId, now) }
        }
    }

    suspend fun me(userId: UserId): Account = tx.inTransaction { accounts.findById(userId) ?: notFound("Account") }

    suspend fun updateProfile(userId: UserId, update: ProfileUpdate, expectedVersion: Long?): Account {
        val v = Validation()
        update.displayName?.let { AccountRules.validateDisplayName(it, v) }
        update.phone?.let { AccountRules.validatePhone(it, v) }
        update.shippingAddress?.let { Address.validate(it, "shippingAddress", v) }
        update.preferences?.let { AccountRules.validateCategories(it.favoriteCategories, v) }
        v.throwIfInvalid()
        return mutate(userId, expectedVersion, "account.profile-updated") {
            it.copy(
                displayName = update.displayName?.trim() ?: it.displayName,
                phone = update.phone?.trim() ?: it.phone,
                shippingAddress = update.shippingAddress?.trimmed() ?: it.shippingAddress,
                preferences = update.preferences ?: it.preferences,
            )
        }
    }

    suspend fun updateSellerProfile(userId: UserId, profile: SellerProfile, expectedVersion: Long?): Account {
        val v = Validation()
        AccountRules.validateSellerProfile(profile, v)
        v.throwIfInvalid()
        return mutate(userId, expectedVersion, "account.seller-profile-updated") {
            it.copy(sellerProfile = profile.copy(bio = profile.bio.trim(), originAddress = profile.originAddress.trimmed()))
        }
    }

    /** USR-010: operator action; revokes every refresh token immediately. */
    suspend fun suspend(userId: UserId, operator: String, reason: String?): Account {
        val now = clock.instant()
        return tx.inTransaction {
            val account = accounts.findById(userId, forUpdate = true) ?: notFound("Account")
            if (account.status == AccountStatus.SUSPENDED) return@inTransaction account
            val next = account.suspend()
            saveOrConflict(next, account.version)
            accounts.revokeAllForUser(userId, now)
            audit.record(Actor.System("operator:$operator"), "account.suspended", "account", userId.toString(), mapOf("reason" to reason))
            events.publish(OnboardingEvents.ACCOUNT_SUSPENDED, 1, userId.toString(), """{"userId":"$userId"}""")
            next.copy(version = account.version + 1)
        }
    }

    suspend fun reactivate(userId: UserId, operator: String): Account = tx.inTransaction {
        val account = accounts.findById(userId, forUpdate = true) ?: notFound("Account")
        if (account.status == AccountStatus.ACTIVE) return@inTransaction account
        val next = account.reactivate()
            ?: throw DomainException(ErrorKind.CONFLICT, "EMAIL_NOT_VERIFIED", "Only accounts with a verified email can be reactivated.")
        saveOrConflict(next, account.version)
        audit.record(Actor.System("operator:$operator"), "account.reactivated", "account", userId.toString())
        next.copy(version = account.version + 1)
    }

    private suspend fun mutate(userId: UserId, expectedVersion: Long?, action: String, change: (Account) -> Account): Account =
        tx.inTransaction {
            val account = accounts.findById(userId, forUpdate = true) ?: notFound("Account")
            if (expectedVersion != null && expectedVersion != account.version) versionConflict()
            val next = change(account)
            if (next == account) return@inTransaction account
            saveOrConflict(next, account.version)
            audit.record(Actor.User(userId), action, "account", userId.toString())
            if (!account.sellerEligible && next.sellerEligible) {
                audit.record(Actor.User(userId), "account.seller-enabled", "account", userId.toString())
            }
            next.copy(version = account.version + 1)
        }

    private fun saveOrConflict(next: Account, expectedVersion: Long) {
        if (!accounts.update(next, expectedVersion)) versionConflict()
    }

    private fun newSession(account: Account, now: Instant, familyId: UUID): Session {
        val refresh = Secrets.randomToken()
        val expiresAt = now + settings.refreshTokenTtl
        accounts.insertRefreshToken(UUID.randomUUID(), familyId, account.id, Secrets.sha256Hex(refresh), expiresAt, now)
        return Session(tokens.issue(account.id, now), tokens.ttlSeconds, refresh, expiresAt, account)
    }

    private fun sendVerification(account: Account, token: String) {
        email.sendVerification(account.email, account.displayName, "${settings.verificationLinkBase}?token=$token")
    }

    private fun Address.trimmed() = copy(line1 = line1.trim(), line2 = line2?.trim()?.ifEmpty { null }, city = city.trim(), region = region.trim(), postalCode = postalCode.trim())

    private fun invalidRefresh() = DomainException(ErrorKind.UNAUTHENTICATED, "REFRESH_TOKEN_INVALID", "Your session has ended. Sign in again.")
    private fun suspended(): Nothing = throw DomainException(ErrorKind.FORBIDDEN, "ACCOUNT_SUSPENDED", "This account is suspended.")
    private fun versionConflict(): Nothing = conflict("VERSION_CONFLICT", "The account changed since you loaded it. Reload and try again.")
}
