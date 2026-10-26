-- A short claim transaction releases the connection before the broker HTTP call.
-- An expired lease is reclaimable after a worker crash; consumers deduplicate event_id.
ALTER TABLE outbox_events ADD COLUMN claim_token UUID;
ALTER TABLE outbox_events ADD COLUMN claim_until TIMESTAMPTZ;
