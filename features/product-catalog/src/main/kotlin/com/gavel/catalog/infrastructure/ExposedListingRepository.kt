package com.gavel.catalog.infrastructure

import com.gavel.catalog.application.Category
import com.gavel.catalog.application.ListingRepository
import com.gavel.catalog.application.ListingSearch
import com.gavel.catalog.application.ListingSort
import com.gavel.catalog.domain.ItemCondition
import com.gavel.catalog.domain.Listing
import com.gavel.catalog.domain.ListingContent
import com.gavel.catalog.domain.ListingStatus
import com.gavel.catalog.domain.Origin
import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.Cursor
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.Page
import com.gavel.kernel.PageRequest
import com.gavel.kernel.UserId
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

internal object CategoriesTable : Table("catalog.categories") {
    val slug = varchar("slug", 40)
    val name = varchar("name", 60)
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(slug)
}

internal object ListingsTable : Table("catalog.listings") {
    val id = javaUUID("id")
    val sellerId = javaUUID("seller_id")
    val status = varchar("status", 16)
    val title = varchar("title", 80).nullable()
    val description = text("description").nullable()
    val categorySlug = varchar("category_slug", 40).nullable()
    val condition = varchar("condition", 16).nullable()
    val imageUrls = array<String>("image_urls")
    val currency = varchar("currency", 3).nullable()
    val startingPriceMinor = long("starting_price_minor").nullable()
    val shippingPriceMinor = long("shipping_price_minor").nullable()
    val freeShipping = bool("free_shipping")
    val originCity = varchar("origin_city", 100).nullable()
    val originRegion = varchar("origin_region", 100).nullable()
    val originCountry = varchar("origin_country", 2).nullable()
    val startAt = timestamp("start_at").nullable()
    val endAt = timestamp("end_at").nullable()
    val currentPriceMinor = long("current_price_minor").nullable()
    val bidCount = integer("bid_count")
    val publishedAt = timestamp("published_at").nullable()
    val closedAt = timestamp("closed_at").nullable()
    val cancelledAt = timestamp("cancelled_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

internal class ExposedListingRepository : ListingRepository {
    private val t = ListingsTable

    override fun insert(listing: Listing) {
        t.insert {
            it[id] = listing.id.value
            it[sellerId] = listing.sellerId.value
            it[createdAt] = listing.createdAt
            write(it, listing)
        }
    }

    override fun update(listing: Listing, expectedVersion: Long): Boolean =
        t.update({ (t.id eq listing.id.value) and (t.version eq expectedVersion) }) { write(it, listing) } == 1

    private fun write(it: UpdateBuilder<*>, l: Listing) = with(t) {
        val c = l.content
        it[status] = l.status.name
        it[title] = c.title
        it[description] = c.description
        it[categorySlug] = c.categorySlug
        it[condition] = c.condition?.name
        it[imageUrls] = c.imageUrls
        it[currency] = (c.startingPrice ?: c.shippingPrice)?.currency?.code
        it[startingPriceMinor] = c.startingPrice?.amountMinor
        it[shippingPriceMinor] = c.shippingPrice?.amountMinor
        it[freeShipping] = c.freeShipping
        it[originCity] = c.origin?.city
        it[originRegion] = c.origin?.region
        it[originCountry] = c.origin?.country
        it[startAt] = c.startAt
        it[endAt] = c.endAt
        it[currentPriceMinor] = l.currentPrice?.amountMinor
        it[bidCount] = l.bidCount
        it[publishedAt] = l.publishedAt
        it[closedAt] = l.closedAt
        it[cancelledAt] = l.cancelledAt
        it[updatedAt] = l.updatedAt
        it[version] = l.version
    }

    override fun find(id: ListingId, forUpdate: Boolean): Listing? =
        t.selectAll().where { t.id eq id.value }.let { if (forUpdate) it.forUpdate() else it }.singleOrNull()?.toListing()

    override fun search(query: ListingSearch, page: PageRequest): Page<Listing> {
        var where: Op<Boolean> = t.status inList query.statuses.map { it.name }
        query.text?.let { text ->
            val pattern = "%" + text.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where = where and ((t.title.lowerCase() like pattern) or (t.description.lowerCase() like pattern))
        }
        query.category?.let { where = where and (t.categorySlug eq it) }
        query.minPriceMinor?.let { where = where and (t.currentPriceMinor greaterEq it) }
        query.maxPriceMinor?.let { where = where and (t.currentPriceMinor lessEq it) }
        query.endsBefore?.let { where = where and (t.endAt less it) }

        return when (query.sort) {
            ListingSort.ENDING_SOON -> keyset(where, page, t.endAt, ascending = true, Instant::parse, Instant::toString)
            ListingSort.NEWEST -> keyset(where, page, t.publishedAt, ascending = false, Instant::parse, Instant::toString)
            ListingSort.PRICE_ASC -> keyset(where, page, t.currentPriceMinor, ascending = true, String::toLong, Long::toString)
            ListingSort.PRICE_DESC -> keyset(where, page, t.currentPriceMinor, ascending = false, String::toLong, Long::toString)
        }
    }

    override fun bySeller(sellerId: UserId, page: PageRequest): Page<Listing> =
        keyset(t.sellerId eq sellerId.value, page, t.updatedAt, ascending = false, Instant::parse, Instant::toString)

    /** Stable keyset pagination on (sort column, id); the sort column is non-null for public rows. */
    @Suppress("UNCHECKED_CAST")
    private fun <V : Comparable<V>> keyset(
        base: Op<Boolean>,
        page: PageRequest,
        sortColumn: Column<*>,
        ascending: Boolean,
        parse: (String) -> V,
        format: (V) -> String,
    ): Page<Listing> {
        val column = sortColumn as Column<V>
        var where = base
        page.cursor?.let { raw ->
            val (value, id) = Cursor.decode(raw, 2) { parse(it[0]) to UUID.fromString(it[1]) }
            where = where and if (ascending) {
                (column greater value) or ((column eq value) and (t.id greater id))
            } else {
                (column less value) or ((column eq value) and (t.id less id))
            }
        }
        val order = if (ascending) SortOrder.ASC else SortOrder.DESC
        val rows = t.selectAll().where(where).orderBy(column to order, t.id to order).limit(page.limit + 1).toList()
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { Cursor.encode(format(it[column]), it[t.id].toString()) } else null
        return Page(items.map { it.toListing() }, next)
    }

    override fun lockNextDue(status: ListingStatus, now: Instant): Listing? {
        val dueColumn = if (status == ListingStatus.SCHEDULED) t.startAt else t.endAt
        return t.selectAll()
            .where { (t.status eq status.name) and (dueColumn lessEq now) }
            .orderBy(dueColumn to SortOrder.ASC)
            .limit(1)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .singleOrNull()?.toListing()
    }

    override fun applyBidProjection(listingId: ListingId, priceMinor: Long, bidCount: Int) {
        TransactionManager.current().exec(
            "UPDATE catalog.listings SET current_price_minor = GREATEST(COALESCE(current_price_minor, 0), ?), " +
                "bid_count = GREATEST(bid_count, ?) WHERE id = ?",
            listOf(
                org.jetbrains.exposed.v1.core.LongColumnType() to priceMinor,
                org.jetbrains.exposed.v1.core.IntegerColumnType() to bidCount,
                org.jetbrains.exposed.v1.core.java.UUIDColumnType() to listingId.value,
            ),
        )
    }

    override fun categories(): List<Category> =
        CategoriesTable.selectAll().orderBy(CategoriesTable.sortOrder).map { Category(it[CategoriesTable.slug], it[CategoriesTable.name]) }

    private fun ResultRow.toListing(): Listing {
        val currency = this[t.currency]?.let(::CurrencyCode)
        fun money(v: Long?) = if (v != null && currency != null) Money(v, currency) else null
        return Listing(
            id = ListingId(this[t.id]),
            sellerId = UserId(this[t.sellerId]),
            status = ListingStatus.valueOf(this[t.status]),
            content = ListingContent(
                title = this[t.title],
                description = this[t.description],
                categorySlug = this[t.categorySlug],
                condition = this[t.condition]?.let(ItemCondition::valueOf),
                imageUrls = this[t.imageUrls],
                startingPrice = money(this[t.startingPriceMinor]),
                shippingPrice = money(this[t.shippingPriceMinor]),
                freeShipping = this[t.freeShipping],
                origin = this[t.originCity]?.let { Origin(it, this[t.originRegion]!!, this[t.originCountry]!!) },
                startAt = this[t.startAt],
                endAt = this[t.endAt],
            ),
            currentPrice = money(this[t.currentPriceMinor]),
            bidCount = this[t.bidCount],
            publishedAt = this[t.publishedAt],
            closedAt = this[t.closedAt],
            cancelledAt = this[t.cancelledAt],
            createdAt = this[t.createdAt],
            updatedAt = this[t.updatedAt],
            version = this[t.version],
        )
    }
}
