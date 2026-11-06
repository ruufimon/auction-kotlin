-- Transactional outbox: events commit atomically with the state change that produced them.
CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    event_type      varchar(100) NOT NULL,
    payload_version integer      NOT NULL,
    aggregate_id    varchar(100) NOT NULL,
    occurred_at     timestamptz  NOT NULL,
    correlation_id  varchar(100) NOT NULL,
    payload         jsonb        NOT NULL,
    status          varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL,
    last_error      varchar(1000),
    delivered_at    timestamptz
);
CREATE INDEX outbox_events_pending_idx ON outbox_events (next_attempt_at) WHERE status = 'PENDING';

-- Append-only audit trail (actor, timestamp, entity and transition details).
CREATE TABLE audit_log (
    id             bigserial PRIMARY KEY,
    occurred_at    timestamptz  NOT NULL,
    actor_type     varchar(16)  NOT NULL,
    actor_id       varchar(100) NOT NULL,
    action         varchar(100) NOT NULL,
    entity_type    varchar(40)  NOT NULL,
    entity_id      varchar(100) NOT NULL,
    details        jsonb        NOT NULL,
    correlation_id varchar(100) NOT NULL
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX outbox_events_dead_idx ON outbox_events (occurred_at) WHERE status = 'DEAD';
CREATE INDEX outbox_events_delivered_idx ON outbox_events (delivered_at) WHERE status = 'DELIVERED';
