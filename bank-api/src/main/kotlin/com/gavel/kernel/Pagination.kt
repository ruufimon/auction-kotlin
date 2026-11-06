package com.gavel.kernel

import java.util.Base64

/** A bounded page of results with an opaque cursor for the next page. */
data class Page<T>(val items: List<T>, val nextCursor: String?)

data class PageRequest(val limit: Int, val cursor: String?) {
    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        fun of(limit: Int?, cursor: String?, max: Int = MAX_LIMIT): PageRequest {
            val effective = limit ?: DEFAULT_LIMIT
            if (effective !in 1..max) {
                throw DomainException(
                    ErrorKind.MALFORMED, "PAGE_SIZE_INVALID", "limit must be between 1 and $max.",
                    listOf(FieldError("limit", "OUT_OF_RANGE", "limit must be between 1 and $max.")),
                )
            }
            return PageRequest(effective, cursor?.takeIf { it.isNotBlank() })
        }
    }
}

/** Keyset cursors: opaque base64url tokens holding the last row's sort key parts. */
object Cursor {
    private const val SEPARATOR = '\u001F'

    fun encode(vararg parts: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString(SEPARATOR.toString()).toByteArray())

    fun decode(cursor: String, expectedParts: Int): List<String> {
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor)).split(SEPARATOR) }.getOrNull()
        if (parts == null || parts.size != expectedParts) invalid()
        return parts
    }

    /** Decodes and converts a cursor, treating any conversion failure as an invalid cursor. */
    fun <T> decode(cursor: String, expectedParts: Int, convert: (List<String>) -> T): T =
        runCatching { convert(decode(cursor, expectedParts)) }.getOrElse { if (it is DomainException) throw it else invalid() }

    fun invalid(): Nothing = throw DomainException(
        ErrorKind.MALFORMED, "CURSOR_INVALID", "The pagination cursor is invalid.",
        listOf(FieldError("cursor", "INVALID", "The pagination cursor is invalid.")),
    )
}
