CREATE TABLE accounts (
    id                  uuid PRIMARY KEY,
    email               varchar(254) NOT NULL UNIQUE,
    password_hash       varchar(200) NOT NULL,
    status              varchar(32)  NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED')),
    email_verified_at   timestamptz,
    display_name        varchar(60)  NOT NULL,
    phone               varchar(32),
    ship_line1          varchar(200),
    ship_line2          varchar(200),
    ship_city           varchar(100),
    ship_region         varchar(100),
    ship_postal_code    varchar(20),
    ship_country        char(2),
    favorite_categories text[]       NOT NULL DEFAULT '{}',
    outbid_alerts       boolean      NOT NULL DEFAULT true,
    ending_soon_alerts  boolean      NOT NULL DEFAULT true,
    seller_bio          varchar(280),
    seller_handling_days integer,
    origin_line1        varchar(200),
    origin_line2        varchar(200),
    origin_city         varchar(100),
    origin_region       varchar(100),
    origin_postal_code  varchar(20),
    origin_country      char(2),
    terms_accepted_at   timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL
);

CREATE TABLE email_verification_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES accounts (id),
    token_hash  char(64)    NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL
);
CREATE INDEX email_verification_tokens_user_idx ON email_verification_tokens (user_id);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    family_id   uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES accounts (id),
    token_hash  char(64)    NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,
    rotated_at  timestamptz,
    revoked_at  timestamptz
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
