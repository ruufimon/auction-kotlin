CREATE TABLE categories (
    slug       varchar(40) PRIMARY KEY,
    name       varchar(60) NOT NULL,
    sort_order integer     NOT NULL
);

INSERT INTO categories (slug, name, sort_order) VALUES
    ('cameras', 'Cameras', 10),
    ('watches', 'Watches', 20),
    ('design', 'Design', 30),
    ('records', 'Records', 40),
    ('art', 'Art', 50),
    ('jewelry', 'Jewelry', 60),
    ('collectibles', 'Collectibles', 70),
    ('other', 'Other', 1000);

CREATE TABLE listings (
    id                   uuid PRIMARY KEY,
    seller_id            uuid         NOT NULL,
    status               varchar(16)  NOT NULL CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'SOLD', 'UNSOLD', 'CANCELLED')),
    title                varchar(80),
    description          text,
    category_slug        varchar(40) REFERENCES categories (slug),
    condition            varchar(16),
    image_urls           text[]       NOT NULL DEFAULT '{}',
    currency             char(3),
    starting_price_minor bigint CHECK (starting_price_minor > 0),
    shipping_price_minor bigint CHECK (shipping_price_minor >= 0),
    free_shipping        boolean      NOT NULL DEFAULT false,
    origin_city          varchar(100),
    origin_region        varchar(100),
    origin_country       char(2),
    start_at             timestamptz,
    end_at               timestamptz,
    -- Search projection, maintained from bidding.bid-accepted events.
    current_price_minor  bigint,
    bid_count            integer      NOT NULL DEFAULT 0,
    published_at         timestamptz,
    closed_at            timestamptz,
    cancelled_at         timestamptz,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    version              bigint       NOT NULL,
    CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR (start_at IS NOT NULL AND end_at > start_at))
);

CREATE INDEX listings_public_end_idx ON listings (status, end_at, id);
CREATE INDEX listings_public_published_idx ON listings (status, published_at DESC, id DESC);
CREATE INDEX listings_public_price_idx ON listings (status, current_price_minor, id);
CREATE INDEX listings_category_idx ON listings (category_slug, status, end_at);
CREATE INDEX listings_seller_idx ON listings (seller_id, updated_at DESC, id DESC);
CREATE INDEX listings_due_start_idx ON listings (start_at) WHERE status = 'SCHEDULED';
CREATE INDEX listings_due_end_idx ON listings (end_at) WHERE status = 'ACTIVE';
