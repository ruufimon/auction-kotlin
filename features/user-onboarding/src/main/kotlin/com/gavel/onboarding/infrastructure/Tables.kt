package com.gavel.onboarding.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

internal object AccountsTable : Table("onboarding.accounts") {
    val id = javaUUID("id")
    val email = varchar("email", 254)
    val passwordHash = varchar("password_hash", 200)
    val status = varchar("status", 32)
    val emailVerifiedAt = timestamp("email_verified_at").nullable()
    val displayName = varchar("display_name", 60)
    val phone = varchar("phone", 32).nullable()
    val shipLine1 = varchar("ship_line1", 200).nullable()
    val shipLine2 = varchar("ship_line2", 200).nullable()
    val shipCity = varchar("ship_city", 100).nullable()
    val shipRegion = varchar("ship_region", 100).nullable()
    val shipPostalCode = varchar("ship_postal_code", 20).nullable()
    val shipCountry = varchar("ship_country", 2).nullable()
    val favoriteCategories = array<String>("favorite_categories")
    val outbidAlerts = bool("outbid_alerts")
    val endingSoonAlerts = bool("ending_soon_alerts")
    val sellerBio = varchar("seller_bio", 280).nullable()
    val sellerHandlingDays = integer("seller_handling_days").nullable()
    val originLine1 = varchar("origin_line1", 200).nullable()
    val originLine2 = varchar("origin_line2", 200).nullable()
    val originCity = varchar("origin_city", 100).nullable()
    val originRegion = varchar("origin_region", 100).nullable()
    val originPostalCode = varchar("origin_postal_code", 20).nullable()
    val originCountry = varchar("origin_country", 2).nullable()
    val termsAcceptedAt = timestamp("terms_accepted_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

internal object VerificationTokensTable : Table("onboarding.email_verification_tokens") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object RefreshTokensTable : Table("onboarding.refresh_tokens") {
    val id = javaUUID("id")
    val familyId = javaUUID("family_id")
    val userId = javaUUID("user_id")
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val rotatedAt = timestamp("rotated_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
