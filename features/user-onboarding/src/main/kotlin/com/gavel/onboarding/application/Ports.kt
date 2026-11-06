package com.gavel.onboarding.application

import com.gavel.kernel.UserId
import com.gavel.onboarding.domain.Account
import java.time.Instant
import java.util.UUID

interface PasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, encoded: String): Boolean
}

interface AccessTokens {
    val ttlSeconds: Long
    fun issue(userId: UserId, now: Instant): String
}

/** Provider-neutral email delivery, always called outside database transactions. */
interface EmailSender {
    fun sendVerification(to: String, displayName: String, verificationLink: String)
}

data class StoredRefreshToken(
    val id: UUID,
    val familyId: UUID,
    val userId: UserId,
    val expiresAt: Instant,
    val rotatedAt: Instant?,
    val revokedAt: Instant?,
)

interface AccountRepository {
    fun insert(account: Account)
    fun update(account: Account, expectedVersion: Long): Boolean
    fun findById(id: UserId, forUpdate: Boolean = false): Account?
    fun findByEmail(email: String): Account?
    fun findByIds(ids: Collection<UserId>): List<Account>

    fun insertVerificationToken(userId: UserId, tokenHash: String, expiresAt: Instant, now: Instant)
    /** Marks the token used and returns its owner, or null if it is unknown, used or expired. */
    fun consumeVerificationToken(tokenHash: String, now: Instant): UserId?
    fun discardUnusedVerificationTokens(userId: UserId)

    fun insertRefreshToken(id: UUID, familyId: UUID, userId: UserId, tokenHash: String, expiresAt: Instant, now: Instant)
    fun findRefreshTokenForUpdate(tokenHash: String): StoredRefreshToken?
    fun markRotated(id: UUID, now: Instant)
    fun revokeFamily(familyId: UUID, now: Instant)
    fun revokeAllForUser(userId: UserId, now: Instant)
}
