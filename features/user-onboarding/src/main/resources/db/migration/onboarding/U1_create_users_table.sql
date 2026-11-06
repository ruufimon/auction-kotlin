-- User-Onboarding: Users table
-- Requirement USR-001, USR-003, USR-005, USR-010
-- Stores user accounts with authentication and seller status

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Public-facing identifier (opaque, non-sequential per requirement)
    user_id VARCHAR(64) NOT NULL UNIQUE,
    
    -- Authentication
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    
    -- Profile
    display_name VARCHAR(255),
    
    -- Status (USR-010: active|suspended)
    status VARCHAR(32) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
    
    -- Seller capabilities (USR-006, USR-008)
    seller_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    seller_enabled_at TIMESTAMP WITH TIME ZONE,
    
    -- Email verification (USR-003, USR-004)
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps (requirement: UTC)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for authentication and lookups
CREATE INDEX idx_users_email ON users(email) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_user_id ON users(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_status ON users(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_seller_enabled ON users(seller_enabled) WHERE deleted_at IS NULL;
