CREATE TABLE auctions (
    listing_id           uuid PRIMARY KEY,
    seller_id            uuid        NOT NULL,
    currency             char(3)     NOT NULL,
    starting_price_minor bigint      NOT NULL CHECK (starting_price_minor > 0),
    end_at               timestamptz NOT NULL,
    state                varchar(16) NOT NULL CHECK (state IN ('OPEN', 'CLOSED', 'CANCELLED')),
    highest_bid_id       uuid,
    highest_bid_minor    bigint,
    highest_bidder_id    uuid,
    bid_count            integer     NOT NULL DEFAULT 0,
    last_sequence        bigint      NOT NULL DEFAULT 0,
    opened_at            timestamptz NOT NULL,
    closed_at            timestamptz
);

CREATE TABLE bids (
    id               uuid PRIMARY KEY,
    listing_id       uuid         NOT NULL REFERENCES auctions (listing_id),
    bidder_id        uuid         NOT NULL,
    bidder_alias     integer      NOT NULL,
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0),
    currency         char(3)      NOT NULL,
    sequence         bigint       NOT NULL,
    accepted_at      timestamptz  NOT NULL,
    idempotency_key  varchar(200) NOT NULL,
    UNIQUE (listing_id, sequence),
    UNIQUE (bidder_id, idempotency_key)
);
CREATE INDEX bids_bidder_idx ON bids (bidder_id, accepted_at DESC, id);
