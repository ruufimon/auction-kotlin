CREATE TABLE orders (
    id                  uuid PRIMARY KEY,
    listing_id          uuid         NOT NULL UNIQUE,
    title               varchar(80)  NOT NULL,
    winning_bid_id      uuid         NOT NULL UNIQUE,
    buyer_id            uuid         NOT NULL,
    seller_id           uuid         NOT NULL,
    currency            char(3)      NOT NULL,
    item_price_minor    bigint       NOT NULL CHECK (item_price_minor > 0),
    shipping_price_minor bigint      NOT NULL CHECK (shipping_price_minor >= 0),
    status              varchar(20)  NOT NULL CHECK (status IN ('PAYMENT_PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'PAYMENT_EXPIRED')),
    payment_deadline    timestamptz  NOT NULL,
    ship_name           varchar(60),
    ship_phone          varchar(32),
    ship_line1          varchar(200),
    ship_line2          varchar(200),
    ship_city           varchar(100),
    ship_region         varchar(100),
    ship_postal_code    varchar(20),
    ship_country        char(2),
    paid_at             timestamptz,
    carrier             varchar(60),
    tracking_reference  varchar(100),
    shipped_at          timestamptz,
    delivered_at        timestamptz,
    delivery_source     varchar(16),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL
);
CREATE INDEX orders_buyer_idx ON orders (buyer_id, created_at DESC, id DESC);
CREATE INDEX orders_seller_idx ON orders (seller_id, created_at DESC, id DESC);
CREATE INDEX orders_due_expiry_idx ON orders (payment_deadline) WHERE status = 'PAYMENT_PENDING';

CREATE TABLE payment_attempts (
    id                 uuid PRIMARY KEY,
    order_id           uuid          NOT NULL REFERENCES orders (id),
    attempt_number     integer       NOT NULL,
    provider           varchar(40)   NOT NULL,
    provider_reference varchar(200)  NOT NULL,
    checkout_url       varchar(2000) NOT NULL,
    amount_minor       bigint        NOT NULL,
    currency           char(3)       NOT NULL,
    status             varchar(16)   NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,
    UNIQUE (order_id, attempt_number),
    UNIQUE (provider, provider_reference)
);

-- Deduplicates provider webhook deliveries (SET-005).
CREATE TABLE provider_events (
    provider    varchar(40)  NOT NULL,
    event_id    varchar(200) NOT NULL,
    received_at timestamptz  NOT NULL,
    PRIMARY KEY (provider, event_id)
);
