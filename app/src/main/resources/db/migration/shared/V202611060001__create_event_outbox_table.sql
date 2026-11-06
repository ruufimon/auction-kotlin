-- Shared: Event outbox table
-- Transactional outbox pattern: events committed atomically with state changes
-- Requirement: Outbox pattern for reliable async communication between features
-- See DOMAIN_EVENTS.md for event architecture

CREATE TABLE event_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event identity and classification
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(255) NOT NULL,  -- e.g., "catalog.auction-sold", "bidding.bid-placed"
    aggregate_type VARCHAR(64) NOT NULL,  -- e.g., "listing", "bid", "order"
    aggregate_id UUID NOT NULL,  -- Primary entity affected
    payload_version INTEGER NOT NULL,
    
    -- Event payload as JSON
    payload JSONB NOT NULL,
    
    -- Event correlation and causality tracking
    correlation_id UUID,
    causation_id UUID,
    
    -- Publication state (poll-based delivery with DB coordination)
    published_at TIMESTAMP WITH TIME ZONE,
    
    -- Retry tracking (multiple instances can poll safely)
    delivery_attempts INTEGER NOT NULL DEFAULT 0,
    last_delivery_error TEXT,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for outbox polling and delivery coordination
-- Unprocessed events ordered by creation (FIFO for deterministic replay)
CREATE INDEX idx_event_outbox_unpublished ON event_outbox(created_at ASC) WHERE published_at IS NULL;

-- Event lookup by aggregate (e.g., all events for listing #123)
CREATE INDEX idx_event_outbox_aggregate ON event_outbox(aggregate_type, aggregate_id);

-- Event type filtering for type-specific handlers
CREATE INDEX idx_event_outbox_event_type ON event_outbox(event_type);
