CREATE TABLE contracts (
 contract_id UUID PRIMARY KEY,owner_subject VARCHAR(255) NOT NULL,source_revision BIGINT NOT NULL,
 snapshot JSONB NOT NULL,version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX contracts_owner_idx ON contracts(owner_subject,contract_id);
CREATE TABLE inbox_events(event_id UUID PRIMARY KEY,payload JSONB NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT now(),processed_at TIMESTAMPTZ);
CREATE INDEX inbox_pending_idx ON inbox_events(received_at) WHERE processed_at IS NULL;
CREATE TABLE outbox_events(event_id UUID PRIMARY KEY,aggregate_id UUID NOT NULL,event_type VARCHAR(80) NOT NULL,payload JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),published_at TIMESTAMPTZ,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX outbox_pending_idx ON outbox_events(next_attempt_at,created_at) WHERE published_at IS NULL;
