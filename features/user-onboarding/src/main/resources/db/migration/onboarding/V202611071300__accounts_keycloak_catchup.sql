-- User-Onboarding: schema catch-up for Keycloak-provided identity
-- Requirement PR #60 (USR-002, USR-006, USR-007, USR-011, USR-012, USR-013); issue #18
--
-- Credentials and tokens are Keycloak's. The M1 credential/token tables were never
-- applied outside local development (every production deploy failed before migrating),
-- so dropping them is safe. See AGENT.md "Database Migrations".

DROP TABLE refresh_tokens;
DROP TABLE email_verification_tokens;

ALTER TABLE users DROP COLUMN password_hash;

-- Identity: the provider subject is the stable key; user_id stays the opaque public id.
ALTER TABLE users ADD COLUMN idp_subject VARCHAR(255) NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_idp_subject UNIQUE (idp_subject);
COMMENT ON COLUMN users.idp_subject IS 'Identity-provider subject (sub claim); stable key of the account (USR-013)';

-- Four account states (requirement §5.1), default = entry state for an unverified email.
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'pending_verification';
ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('pending_verification', 'pending_credit_eligibility', 'active', 'suspended'));
ALTER TABLE users
    ADD COLUMN suspended_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN suspension_reason VARCHAR(128);

-- Credit eligibility (USR-011, USR-012): outcome is auditable, inputs are ciphertext only.
ALTER TABLE users
    ADD COLUMN credit_outcome VARCHAR(32) CHECK (credit_outcome IN ('ELIGIBLE', 'NOT_ELIGIBLE')),
    ADD COLUMN credit_reason VARCHAR(64),
    ADD COLUMN credit_policy_version VARCHAR(64),
    ADD COLUMN credit_assessed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN age_enc TEXT,
    ADD COLUMN annual_income_enc TEXT,
    ADD COLUMN net_wealth_enc TEXT;
COMMENT ON COLUMN users.age_enc IS 'AES-GCM ciphertext (base64 iv||ct); raw assessment inputs are never stored in clear (USR-012)';
COMMENT ON COLUMN users.annual_income_enc IS 'AES-GCM ciphertext of integer minor units (USR-011, USR-012)';
COMMENT ON COLUMN users.net_wealth_enc IS 'AES-GCM ciphertext of integer minor units (USR-011, USR-012)';

-- Profile, seller profile and origin address (USR-007, USR-008). The seller and origin
-- documents are JSON because the required field set is deployment configuration.
ALTER TABLE users
    ADD COLUMN phone VARCHAR(32),
    ADD COLUMN shipping_address JSONB,
    ADD COLUMN seller_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN origin_address JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Optimistic concurrency for profile updates (§6.5: 409 on conflict).
ALTER TABLE users ADD COLUMN version INTEGER NOT NULL DEFAULT 1;

-- Identity linking looks up accounts by verified email only (USR-001).
CREATE INDEX idx_users_verified_email ON users (email) WHERE email_verified AND deleted_at IS NULL;
