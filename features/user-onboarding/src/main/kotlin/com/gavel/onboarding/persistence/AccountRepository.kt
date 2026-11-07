package com.gavel.onboarding.persistence

import com.gavel.kernel.persistence.jsonb
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.AccountState
import com.gavel.onboarding.domain.Address
import com.gavel.onboarding.domain.CreditAssessment
import com.gavel.onboarding.domain.Email
import com.gavel.onboarding.domain.EligibilityOutcome
import com.gavel.onboarding.domain.IdpSubject
import com.gavel.onboarding.domain.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update

/**
 * Persistence port for [Account]. All methods run inside the caller's Exposed transaction.
 * Raw assessment inputs are written encrypted and never read back by this repository.
 */
interface AccountRepository {
    fun findBySubject(subject: IdpSubject): Account?
    fun findByUserId(userId: UserId): Account?

    /** Linking lookup (USR-001): only accounts whose email is verified. */
    fun findByVerifiedEmail(email: Email): Account?

    /** Throws the database's unique-violation when the subject or email already exists. */
    fun insert(account: Account)

    /** Optimistic update: true when the row at `account.version - 1` was replaced, false on conflict. */
    fun update(account: Account): Boolean
}

object AccountsTable : Table("users") {
    val id = uuid("id")
    val userId = varchar("user_id", 64)
    val idpSubject = varchar("idp_subject", 255)
    val email = varchar("email", 255)
    val emailVerified = bool("email_verified")
    val emailVerifiedAt = timestamp("email_verified_at").nullable()
    val displayName = varchar("display_name", 255).nullable()
    val status = varchar("status", 32)
    val sellerEnabled = bool("seller_enabled")
    val sellerEnabledAt = timestamp("seller_enabled_at").nullable()
    val creditOutcome = varchar("credit_outcome", 32).nullable()
    val creditReason = varchar("credit_reason", 64).nullable()
    val creditPolicyVersion = varchar("credit_policy_version", 64).nullable()
    val creditAssessedAt = timestamp("credit_assessed_at").nullable()
    val ageEnc = text("age_enc").nullable()
    val annualIncomeEnc = text("annual_income_enc").nullable()
    val netWealthEnc = text("net_wealth_enc").nullable()
    val phone = varchar("phone", 32).nullable()
    val shippingAddress = jsonb("shipping_address").nullable()
    val sellerProfile = jsonb("seller_profile")
    val originAddress = jsonb("origin_address")
    val suspendedAt = timestamp("suspended_at").nullable()
    val suspensionReason = varchar("suspension_reason", 128).nullable()
    val version = integer("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

class ExposedAccountRepository(private val cipher: FieldCipher) : AccountRepository {

    override fun findBySubject(subject: IdpSubject): Account? =
        AccountsTable.select { (AccountsTable.idpSubject eq subject.value) and AccountsTable.deletedAt.isNull() }
            .singleOrNull()?.toAccount()

    override fun findByUserId(userId: UserId): Account? =
        AccountsTable.select { (AccountsTable.userId eq userId.value) and AccountsTable.deletedAt.isNull() }
            .singleOrNull()?.toAccount()

    override fun findByVerifiedEmail(email: Email): Account? =
        AccountsTable
            .select { (AccountsTable.email eq email.value) and (AccountsTable.emailVerified eq true) and AccountsTable.deletedAt.isNull() }
            .singleOrNull()?.toAccount()

    override fun insert(account: Account) {
        AccountsTable.insert {
            it[id] = account.id
            it[createdAt] = account.createdAt
            writeMutable(it, account)
        }
    }

    override fun update(account: Account): Boolean {
        val updated = AccountsTable.update({ (AccountsTable.id eq account.id) and (AccountsTable.version eq account.version - 1) }) {
            writeMutable(it, account)
        }
        return updated == 1
    }

    private fun writeMutable(row: UpdateBuilder<*>, account: Account) {
        row[AccountsTable.userId] = account.userId.value
        row[AccountsTable.idpSubject] = account.subject.value
        row[AccountsTable.email] = account.email.value
        row[AccountsTable.emailVerified] = account.emailVerified
        row[AccountsTable.displayName] = account.displayName
        row[AccountsTable.status] = account.state.dbValue
        row[AccountsTable.sellerEnabled] = account.sellerEnabled
        row[AccountsTable.sellerEnabledAt] = account.sellerEnabledAt
        row[AccountsTable.creditOutcome] = account.assessment?.outcome?.name
        row[AccountsTable.creditReason] = account.assessment?.reason
        row[AccountsTable.creditPolicyVersion] = account.assessment?.policyVersion
        row[AccountsTable.creditAssessedAt] = account.assessment?.assessedAt
        // Inputs are only present right after scoring; encrypt them then and never afterwards.
        account.assessment?.inputs?.let { inputs ->
            row[AccountsTable.ageEnc] = cipher.encrypt(inputs.age.toString())
            row[AccountsTable.annualIncomeEnc] = cipher.encrypt(inputs.annualIncomeMinor.toString())
            row[AccountsTable.netWealthEnc] = cipher.encrypt(inputs.netWealthMinor.toString())
        }
        row[AccountsTable.phone] = account.phone
        row[AccountsTable.shippingAddress] = account.shippingAddress?.let { json.encodeToString(AddressDocument.serializer(), AddressDocument.from(it)) }
        row[AccountsTable.sellerProfile] = json.encodeToString(mapSerializer, account.sellerProfile)
        row[AccountsTable.originAddress] = json.encodeToString(mapSerializer, account.originAddress)
        row[AccountsTable.suspendedAt] = account.suspendedAt
        row[AccountsTable.suspensionReason] = account.suspensionReason
        row[AccountsTable.version] = account.version
        row[AccountsTable.updatedAt] = account.updatedAt
    }

    private fun ResultRow.toAccount(): Account = Account(
        id = this[AccountsTable.id],
        userId = UserId(this[AccountsTable.userId]),
        subject = IdpSubject(this[AccountsTable.idpSubject]),
        email = Email.of(this[AccountsTable.email]),
        emailVerified = this[AccountsTable.emailVerified],
        displayName = this[AccountsTable.displayName],
        state = AccountState.fromDb(this[AccountsTable.status]),
        phone = this[AccountsTable.phone],
        shippingAddress = this[AccountsTable.shippingAddress]?.let { json.decodeFromString(AddressDocument.serializer(), it).toAddress() },
        sellerProfile = json.decodeFromString(mapSerializer, this[AccountsTable.sellerProfile]),
        originAddress = json.decodeFromString(mapSerializer, this[AccountsTable.originAddress]),
        sellerEnabled = this[AccountsTable.sellerEnabled],
        sellerEnabledAt = this[AccountsTable.sellerEnabledAt],
        assessment = this[AccountsTable.creditOutcome]?.let {
            CreditAssessment(
                outcome = EligibilityOutcome.valueOf(it),
                reason = this[AccountsTable.creditReason] ?: "",
                policyVersion = this[AccountsTable.creditPolicyVersion] ?: "",
                assessedAt = this[AccountsTable.creditAssessedAt] ?: this[AccountsTable.updatedAt],
                inputs = null,
            )
        },
        suspendedAt = this[AccountsTable.suspendedAt],
        suspensionReason = this[AccountsTable.suspensionReason],
        createdAt = this[AccountsTable.createdAt],
        updatedAt = this[AccountsTable.updatedAt],
        version = this[AccountsTable.version],
    )

    @Serializable
    private data class AddressDocument(val line1: String, val city: String, val region: String, val postalCode: String, val country: String) {
        fun toAddress() = Address(line1, city, region, postalCode, country)
        companion object { fun from(a: Address) = AddressDocument(a.line1, a.city, a.region, a.postalCode, a.country) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
