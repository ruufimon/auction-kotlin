-- User-Onboarding: Refresh tokens table
-- Requirement USR-005: short-lived JWT + rotating refresh tokens
-- Supports token revocation (USR-010: suspension blocks refresh)

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Token identifier for lookup and revocation
    token_family VARCHAR(255) NOT NULL UNIQUE,
    
    -- Current active token version (for rotation)
    version INTEGER NOT NULL DEFAULT 1,
    
    -- Token rotation (USR-005: rotating refresh tokens)
    -- New token invalidates old one in same family
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    
    -- Expiration
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- Revocation (USR-010: account suspension)
    revoked_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for token lookups
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at) WHERE revoked_at IS NULL;
