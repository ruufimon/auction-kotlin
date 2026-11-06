package com.gavel.onboarding.infrastructure

import com.gavel.kernel.UserId
import com.gavel.onboarding.api.AccountStatus
import com.gavel.onboarding.application.AccountRepository
import com.gavel.onboarding.application.StoredRefreshToken
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.Preferences
import com.gavel.onboarding.domain.SellerProfile
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

internal class ExposedAccountRepository(private val clock: java.time.Clock) : AccountRepository {
    override fun insert(account: Account) {
        AccountsTable.insert {
            it[id] = account.id.value
            it[termsAcceptedAt] = account.termsAcceptedAt
            it[createdAt] = account.createdAt
            write(it, account)
        }
    }

    override fun update(account: Account, expectedVersion: Long): Boolean =
        AccountsTable.update({ (AccountsTable.id eq account.id.value) and (AccountsTable.version eq expectedVersion) }) {
            write(it, account.copy(version = expectedVersion + 1))
        } == 1

    private fun write(it: UpdateBuilder<*>, a: Account) = with(AccountsTable) {
        it[email] = a.email
        it[passwordHash] = a.passwordHash
        it[status] = a.status.name
        it[emailVerifiedAt] = a.emailVerifiedAt
        it[displayName] = a.displayName
        it[phone] = a.phone
        it[shipLine1] = a.shippingAddress?.line1
        it[shipLine2] = a.shippingAddress?.line2
        it[shipCity] = a.shippingAddress?.city
        it[shipRegion] = a.shippingAddress?.region
        it[shipPostalCode] = a.shippingAddress?.postalCode
        it[shipCountry] = a.shippingAddress?.country
        it[favoriteCategories] = a.preferences.favoriteCategories
        it[outbidAlerts] = a.preferences.outbidAlerts
        it[endingSoonAlerts] = a.preferences.endingSoonAlerts
        val seller = a.sellerProfile
        it[sellerBio] = seller?.bio
        it[sellerHandlingDays] = seller?.handlingTimeDays
        it[originLine1] = seller?.originAddress?.line1
        it[originLine2] = seller?.originAddress?.line2
        it[originCity] = seller?.originAddress?.city
        it[originRegion] = seller?.originAddress?.region
        it[originPostalCode] = seller?.originAddress?.postalCode
        it[originCountry] = seller?.originAddress?.country
        it[updatedAt] = clock.instant()
        it[version] = a.version
    }

    override fun findById(id: UserId, forUpdate: Boolean): Account? =
        AccountsTable.selectAll().where { AccountsTable.id eq id.value }
            .let { if (forUpdate) it.forUpdate() else it }
            .singleOrNull()?.toAccount()

    override fun findByEmail(email: String): Account? =
        AccountsTable.selectAll().where { AccountsTable.email eq email }.singleOrNull()?.toAccount()

    override fun findByIds(ids: Collection<UserId>): List<Account> =
        if (ids.isEmpty()) emptyList()
        else AccountsTable.selectAll().where { AccountsTable.id inList ids.map { it.value } }.map { it.toAccount() }

    override fun insertVerificationToken(userId: UserId, tokenHash: String, expiresAt: Instant, now: Instant) {
        VerificationTokensTable.insert {
            it[id] = UUID.randomUUID()
            it[this.userId] = userId.value
            it[this.tokenHash] = tokenHash
            it[this.expiresAt] = expiresAt
            it[createdAt] = now
        }
    }

    override fun consumeVerificationToken(tokenHash: String, now: Instant): UserId? {
        val t = VerificationTokensTable
        val row = t.selectAll()
            .where { (t.tokenHash eq tokenHash) and t.usedAt.isNull() and (t.expiresAt greater now) }
            .forUpdate().singleOrNull() ?: return null
        t.update({ t.id eq row[t.id] }) { it[usedAt] = now }
        return UserId(row[t.userId])
    }

    override fun discardUnusedVerificationTokens(userId: UserId) {
        val t = VerificationTokensTable
        t.deleteWhere { (t.userId eq userId.value) and t.usedAt.isNull() }
    }

    override fun insertRefreshToken(id: UUID, familyId: UUID, userId: UserId, tokenHash: String, expiresAt: Instant, now: Instant) {
        RefreshTokensTable.insert {
            it[this.id] = id
            it[this.familyId] = familyId
            it[this.userId] = userId.value
            it[this.tokenHash] = tokenHash
            it[this.expiresAt] = expiresAt
            it[createdAt] = now
        }
    }

    override fun findRefreshTokenForUpdate(tokenHash: String): StoredRefreshToken? {
        val t = RefreshTokensTable
        return t.selectAll().where { t.tokenHash eq tokenHash }.forUpdate().singleOrNull()?.let {
            StoredRefreshToken(it[t.id], it[t.familyId], UserId(it[t.userId]), it[t.expiresAt], it[t.rotatedAt], it[t.revokedAt])
        }
    }

    override fun markRotated(id: UUID, now: Instant) {
        RefreshTokensTable.update({ RefreshTokensTable.id eq id }) { it[rotatedAt] = now }
    }

    override fun revokeFamily(familyId: UUID, now: Instant) {
        val t = RefreshTokensTable
        t.update({ (t.familyId eq familyId) and t.revokedAt.isNull() }) { it[revokedAt] = now }
    }

    override fun revokeAllForUser(userId: UserId, now: Instant) {
        val t = RefreshTokensTable
        t.update({ (t.userId eq userId.value) and t.revokedAt.isNull() }) { it[revokedAt] = now }
    }

    private fun ResultRow.toAccount(): Account = with(AccountsTable) {
        val r = this@toAccount
        Account(
            id = UserId(r[id]),
            email = r[email],
            passwordHash = r[passwordHash],
            status = AccountStatus.valueOf(r[status]),
            emailVerifiedAt = r[emailVerifiedAt],
            displayName = r[displayName],
            phone = r[phone],
            shippingAddress = r[shipLine1]?.let {
                Address(it, r[shipLine2], r[shipCity]!!, r[shipRegion]!!, r[shipPostalCode]!!, r[shipCountry]!!)
            },
            preferences = Preferences(r[favoriteCategories], r[outbidAlerts], r[endingSoonAlerts]),
            sellerProfile = r[sellerBio]?.let { bio ->
                SellerProfile(
                    bio, r[sellerHandlingDays]!!,
                    Address(r[originLine1]!!, r[originLine2], r[originCity]!!, r[originRegion]!!, r[originPostalCode]!!, r[originCountry]!!),
                )
            },
            termsAcceptedAt = r[termsAcceptedAt],
            createdAt = r[createdAt],
            version = r[version],
        )
    }
}
