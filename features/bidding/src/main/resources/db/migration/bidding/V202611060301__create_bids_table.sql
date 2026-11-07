-- Bidding: Bids table
-- Requirement BID-001 through BID-012: bid placement with concurrency control, idempotency
-- BID-006: Bid acceptance uses server timestamp, one transaction
-- BID-007: Concurrent bids serialized via row-level locking

CREATE TABLE bids (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Public bid identifier (opaque)
    bid_id VARCHAR(64) NOT NULL UNIQUE,
    
    -- Auction reference (listings.id from product-catalog)
    listing_id UUID NOT NULL,
    
    -- Bidder (users.id from user-onboarding)
    bidder_id UUID NOT NULL,
    
    -- Bid amount in integer minor units of the deployment currency (no floating point)
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    
    -- BID-006: Server-generated timestamp for bid acceptance
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- BID-012: Idempotency key (same key + same bidder = same bid, no duplicates)
    idempotency_key VARCHAR(255),
    
    -- Optimistic locking / version for BID-007 concurrency control
    version INTEGER NOT NULL DEFAULT 1,
    
    -- Bid state (pending, accepted, rejected)
    state VARCHAR(32) NOT NULL DEFAULT 'accepted' CHECK (state IN ('pending', 'accepted', 'rejected')),
    rejection_reason VARCHAR(255),
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for bid history queries and auction lookups (requirement: prevent unbounded queries)
CREATE INDEX idx_bids_listing_id ON bids(listing_id, received_at DESC);
CREATE INDEX idx_bids_bidder_id ON bids(bidder_id);
-- BID-012: same idempotency key from the same bidder on the same listing maps to one bid
CREATE UNIQUE INDEX ux_bids_idempotency ON bids(listing_id, bidder_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_bids_received_at ON bids(received_at DESC);
