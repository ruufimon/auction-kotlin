-- Shared: Audit log table
-- Requirement 2.2: All state changes recorded for audit and compliance
-- Captures who did what to which aggregate at what time

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Actor (user_id who made the change, or system/null for automated actions)
    actor_id UUID,
    actor_type VARCHAR(32) NOT NULL DEFAULT 'user' CHECK (actor_type IN ('user', 'system', 'scheduler')),
    
    -- Action description
    action VARCHAR(128) NOT NULL,  -- e.g., "user.registered", "listing.published", "bid.placed", "order.paid"
    
    -- Aggregate affected
    aggregate_type VARCHAR(64) NOT NULL,  -- e.g., "user", "listing", "bid", "order"
    aggregate_id UUID NOT NULL,
    
    -- State transition
    before_state JSONB,  -- Previous state snapshot
    after_state JSONB,   -- New state snapshot
    
    -- Contextual metadata
    metadata JSONB,  -- e.g., IP address, user agent, request ID, reason codes
    
    -- Correlation
    correlation_id UUID,
    causation_id UUID,
    
    -- Timestamps (requirement: UTC)
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for audit queries and compliance lookups
-- Find all changes by user
CREATE INDEX idx_audit_log_actor_id ON audit_log(actor_id, created_at DESC) WHERE actor_id IS NOT NULL;

-- Find all changes to an aggregate (e.g., all changes to order #123)
CREATE INDEX idx_audit_log_aggregate ON audit_log(aggregate_type, aggregate_id, created_at DESC);

-- Find changes by action type (e.g., all payments, all suspensions)
CREATE INDEX idx_audit_log_action ON audit_log(action, created_at DESC);

-- Find recent changes for compliance monitoring
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at DESC);
