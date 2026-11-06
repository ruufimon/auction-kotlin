-- Product-Catalog: Listings table
-- Requirement CAT-001, CAT-002, CAT-005, CAT-006, CAT-009, CAT-010
-- Stores auction listings with state machine (draft → scheduled → active → closed/cancelled)

CREATE TABLE listings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Public listing identifier (opaque)
    listing_id VARCHAR(64) NOT NULL UNIQUE,
    
    -- Seller (foreign key to users.id from user-onboarding feature)
    seller_id UUID NOT NULL,
    
    -- Listing details
    title VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(64),
    
    -- Pricing
    starting_price DECIMAL(12, 2) NOT NULL CHECK (starting_price > 0),
    current_highest_bid_amount DECIMAL(12, 2),
    current_highest_bidder_id UUID,
    
    -- Auction state machine (CAT-001: draft, scheduled, active, closed, cancelled)
    state VARCHAR(32) NOT NULL DEFAULT 'draft' CHECK (state IN ('draft', 'scheduled', 'active', 'closed', 'cancelled')),
    
    -- Auction timing (requirement: UTC, server-side deadline check BID-006)
    scheduled_start_at TIMESTAMP WITH TIME ZONE,
    start_at TIMESTAMP WITH TIME ZONE,
    end_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for search, pagination, and state queries (requirement: prevent unbounded queries)
CREATE INDEX idx_listings_seller_id ON listings(seller_id) WHERE state != 'cancelled';
CREATE INDEX idx_listings_state ON listings(state);
CREATE INDEX idx_listings_state_end_at ON listings(state, end_at) WHERE state = 'active';
CREATE INDEX idx_listings_published_at ON listings(published_at) WHERE state IN ('active', 'closed');
CREATE INDEX idx_listings_listing_id ON listings(listing_id);
