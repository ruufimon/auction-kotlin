-- User-Onboarding: Email verification tokens table
-- Requirement USR-003, USR-004: one-time expiring tokens for email verification

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- One-time token for email verification link
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    
    -- Email being verified (may differ from users.email if changing)
    email VARCHAR(255) NOT NULL,
    
    -- Expiration (typically 24 hours)
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- One-time use: consumed_at instead of revoked_at
    consumed_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for token lookups
CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens(user_id) WHERE consumed_at IS NULL;
CREATE INDEX idx_email_verification_tokens_token_hash ON email_verification_tokens(token_hash) WHERE consumed_at IS NULL;
CREATE INDEX idx_email_verification_tokens_expires_at ON email_verification_tokens(expires_at) WHERE consumed_at IS NULL;
