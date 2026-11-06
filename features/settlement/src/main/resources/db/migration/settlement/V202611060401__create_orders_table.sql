-- Settlement: Orders table
-- Requirement SET-001 through SET-009: order management, payment state machine, fulfillment tracking
-- Tracks orders from auction close through payment and shipment

CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Public order identifier (opaque)
    order_id VARCHAR(64) NOT NULL UNIQUE,
    
    -- Auction reference (listings.id from product-catalog)
    listing_id UUID NOT NULL,
    
    -- Buyer and seller (users.id from user-onboarding)
    buyer_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    
    -- Winning bid amount (SET-001: order derived from closed auction)
    -- Money in integer minor units of the deployment currency (no floating point)
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    
    -- Payment state machine (SET-006: webhook authoritative, not browser redirect)
    -- unpaid → paid → fulfilled
    payment_status VARCHAR(32) NOT NULL DEFAULT 'unpaid' CHECK (payment_status IN ('unpaid', 'pending', 'paid', 'failed')),
    payment_method VARCHAR(64),
    payment_reference VARCHAR(255),  -- External payment provider reference
    
    -- Fulfillment state machine (SET-008, SET-009)
    fulfillment_status VARCHAR(32) NOT NULL DEFAULT 'pending' CHECK (fulfillment_status IN ('pending', 'shipped', 'delivered')),
    
    -- Shipment tracking
    tracking_number VARCHAR(255),
    shipped_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for user queries (orders by buyer/seller) and state queries (requirement: prevent unbounded queries)
CREATE INDEX idx_orders_buyer_id ON orders(buyer_id, created_at DESC);
CREATE INDEX idx_orders_seller_id ON orders(seller_id, created_at DESC);
CREATE INDEX idx_orders_payment_status ON orders(payment_status);
CREATE INDEX idx_orders_fulfillment_status ON orders(fulfillment_status);
CREATE INDEX idx_orders_order_id ON orders(order_id);
