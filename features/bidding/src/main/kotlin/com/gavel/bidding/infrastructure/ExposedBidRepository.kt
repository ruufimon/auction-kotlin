package com.gavel.bidding.infrastructure

import com.gavel.bidding.api.AuctionState
import com.gavel.bidding.api.OpenAuction
import com.gavel.bidding.application.AcceptedBid
import com.gavel.bidding.application.BidRepository
import com.gavel.bidding.domain.Auction
import com.gavel.kernel.BidId
import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.Cursor
import com.gavel.kernel.ListingId
import com.gavel.kernel.Money
import com.gavel.kernel.Page
import com.gavel.kernel.PageRequest
import com.gavel.kernel.UserId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

internal object AuctionsTable : Table("bidding.auctions") {
    val listingId = javaUUID("listing_id")
    val sellerId = javaUUID("seller_id")
    val currency = varchar("currency", 3)
    val startingPriceMinor = long("starting_price_minor")
    val endAt = timestamp("end_at")
    val state = varchar("state", 16)
    val highestBidId = javaUUID("highest_bid_id").nullable()
    val highestBidMinor = long("highest_bid_minor").nullable()
    val highestBidderId = javaUUID("highest_bidder_id").nullable()
    val bidCount = integer("bid_count")
    val lastSequence = long("last_sequence")
    val openedAt = timestamp("opened_at")
    val closedAt = timestamp("closed_at").nullable()
    override val primaryKey = PrimaryKey(listingId)
}

internal object BidsTable : Table("bidding.bids") {
    val id = javaUUID("id")
    val listingId = javaUUID("listing_id")
    val bidderId = javaUUID("bidder_id")
    val bidderAlias = integer("bidder_alias")
    val amountMinor = long("amount_minor")
    val currency = varchar("currency", 3)
    val sequence = long("sequence")
    val acceptedAt = timestamp("accepted_at")
    val idempotencyKey = varchar("idempotency_key", 200)
    override val primaryKey = PrimaryKey(id)
}

internal class ExposedBidRepository : BidRepository {
    private val a = AuctionsTable
    private val b = BidsTable

    override fun insertAuction(command: OpenAuction, now: Instant): Boolean =
        a.insertIgnore {
            it[listingId] = command.listingId.value
            it[sellerId] = command.sellerId.value
            it[currency] = command.startingPrice.currency.code
            it[startingPriceMinor] = command.startingPrice.amountMinor
            it[endAt] = command.endAt
            it[state] = AuctionState.OPEN.name
            it[bidCount] = 0
            it[lastSequence] = 0
            it[openedAt] = now
        }.insertedCount == 1

    override fun findAuction(listingId: ListingId, forUpdate: Boolean): Auction? =
        a.selectAll().where { a.listingId eq listingId.value }
            .let { if (forUpdate) it.forUpdate() else it }
            .singleOrNull()?.toAuction()

    override fun findAuctions(listingIds: Collection<ListingId>): List<Auction> =
        if (listingIds.isEmpty()) emptyList()
        else a.selectAll().where { a.listingId inList listingIds.map { it.value } }.map { it.toAuction() }

    override fun updateAfterBid(listingId: ListingId, bid: AcceptedBid, bidCount: Int) {
        a.update({ a.listingId eq listingId.value }) {
            it[highestBidId] = bid.bidId.value
            it[highestBidMinor] = bid.amount.amountMinor
            it[highestBidderId] = bid.bidderId.value
            it[this.bidCount] = bidCount
            it[lastSequence] = bid.sequence
        }
    }

    override fun updateState(listingId: ListingId, state: AuctionState, now: Instant) {
        a.update({ a.listingId eq listingId.value }) {
            it[this.state] = state.name
            if (state != AuctionState.OPEN) it[closedAt] = now
        }
    }

    override fun insertBid(bid: AcceptedBid) {
        b.insert {
            it[id] = bid.bidId.value
            it[listingId] = bid.listingId.value
            it[bidderId] = bid.bidderId.value
            it[bidderAlias] = bid.bidderAlias
            it[amountMinor] = bid.amount.amountMinor
            it[currency] = bid.amount.currency.code
            it[sequence] = bid.sequence
            it[acceptedAt] = bid.acceptedAt
            it[idempotencyKey] = bid.idempotencyKey
        }
    }

    override fun findByIdempotencyKey(bidderId: UserId, key: String): AcceptedBid? =
        b.selectAll().where { (b.bidderId eq bidderId.value) and (b.idempotencyKey eq key) }.singleOrNull()?.toBid()

    override fun aliasFor(listingId: ListingId, bidderId: UserId): Int? =
        b.select(b.bidderAlias).where { (b.listingId eq listingId.value) and (b.bidderId eq bidderId.value) }
            .limit(1).singleOrNull()?.get(b.bidderAlias)

    override fun distinctBidderCount(listingId: ListingId): Int {
        val count = b.bidderId.countDistinct()
        return b.select(count).where { b.listingId eq listingId.value }.single()[count].toInt()
    }

    override fun highestBid(listingId: ListingId): AcceptedBid? =
        b.selectAll().where { b.listingId eq listingId.value }
            .orderBy(b.sequence, SortOrder.DESC).limit(1).singleOrNull()?.toBid()

    override fun bidsForListing(listingId: ListingId, page: PageRequest): Page<AcceptedBid> {
        val after = page.cursor?.let { raw -> Cursor.decode(raw, 1) { it[0].toLong() } }
        val rows = b.selectAll()
            .where { (b.listingId eq listingId.value).let { op -> if (after != null) op and (b.sequence less after) else op } }
            .orderBy(b.sequence, SortOrder.DESC)
            .limit(page.limit + 1)
            .map { it.toBid() }
        val items = rows.take(page.limit)
        return Page(items, if (rows.size > page.limit) Cursor.encode(items.last().sequence.toString()) else null)
    }

    override fun bidsForBidder(bidderId: UserId, page: PageRequest): Page<AcceptedBid> {
        val after = page.cursor?.let { raw -> Cursor.decode(raw, 2) { Instant.parse(it[0]) to UUID.fromString(it[1]) } }
        val rows = b.selectAll()
            .where {
                val mine = b.bidderId eq bidderId.value
                if (after == null) mine
                else mine and ((b.acceptedAt less after.first) or ((b.acceptedAt eq after.first) and (b.id less after.second)))
            }
            .orderBy(b.acceptedAt to SortOrder.DESC, b.id to SortOrder.DESC)
            .limit(page.limit + 1)
            .map { it.toBid() }
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { Cursor.encode(it.acceptedAt.toString(), it.bidId.toString()) } else null
        return Page(items, next)
    }

    private fun ResultRow.toAuction(): Auction {
        val currency = CurrencyCode(this[a.currency])
        return Auction(
            listingId = ListingId(this[a.listingId]),
            sellerId = UserId(this[a.sellerId]),
            startingPrice = Money(this[a.startingPriceMinor], currency),
            endAt = this[a.endAt],
            state = AuctionState.valueOf(this[a.state]),
            highestBid = this[a.highestBidMinor]?.let { Money(it, currency) },
            bidCount = this[a.bidCount],
            lastSequence = this[a.lastSequence],
        )
    }

    private fun ResultRow.toBid() = AcceptedBid(
        bidId = BidId(this[b.id]),
        listingId = ListingId(this[b.listingId]),
        bidderId = UserId(this[b.bidderId]),
        bidderAlias = this[b.bidderAlias],
        amount = Money(this[b.amountMinor], CurrencyCode(this[b.currency])),
        sequence = this[b.sequence],
        acceptedAt = this[b.acceptedAt],
        idempotencyKey = this[b.idempotencyKey],
    )
}
