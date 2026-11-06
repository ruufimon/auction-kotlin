package com.gavel.kernel

import java.util.UUID

/**
 * Opaque public identifiers. Random UUIDs never expose sequential keys; the
 * prefix only helps humans tell identifiers apart in logs and URLs.
 */
@JvmInline
value class UserId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = UserId(UUID.randomUUID())
        fun parse(raw: String) = UserId(parseUuid(raw, "userId"))
    }
}

@JvmInline
value class ListingId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = ListingId(UUID.randomUUID())
        fun parse(raw: String) = ListingId(parseUuid(raw, "listingId"))
    }
}

@JvmInline
value class BidId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = BidId(UUID.randomUUID())
    }
}

@JvmInline
value class OrderId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = OrderId(UUID.randomUUID())
        fun parse(raw: String) = OrderId(parseUuid(raw, "orderId"))
    }
}

/** Parses a UUID, reporting unknown identifiers as not found rather than malformed. */
fun parseUuid(raw: String, name: String): UUID =
    runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() }
        ?: throw DomainException(ErrorKind.NOT_FOUND, "NOT_FOUND", "No resource matches $name '$raw'.")
