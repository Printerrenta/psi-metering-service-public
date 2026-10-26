CREATE TABLE readings (
 id UUID PRIMARY KEY,contract_id UUID NOT NULL REFERENCES contracts(contract_id),as_of DATE NOT NULL,
 counters JSONB NOT NULL CHECK(jsonb_typeof(counters)='array' AND jsonb_array_length(counters)=4),
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','ACCEPTED','REJECTED','SUPERSEDED')),
 supersedes UUID REFERENCES readings(id),request_key UUID NOT NULL,submitted_by VARCHAR(255) NOT NULL,
 warnings JSONB NOT NULL,validation JSONB NOT NULL,submitted_at TIMESTAMPTZ NOT NULL,
 reviewed_by VARCHAR(255),review_reason VARCHAR(2000),reviewed_at TIMESTAMPTZ,
 UNIQUE(contract_id,request_key)
);
CREATE UNIQUE INDEX one_accepted_date ON readings(contract_id,as_of) WHERE status='ACCEPTED';
CREATE UNIQUE INDEX one_pending_date ON readings(contract_id,as_of) WHERE status='PENDING';
CREATE INDEX reading_history_idx ON readings(contract_id,as_of DESC,submitted_at DESC,id);
CREATE INDEX reading_review_idx ON readings(submitted_at,id) WHERE status='PENDING';
CREATE TABLE usage_policies(scope VARCHAR(10) NOT NULL CHECK(scope IN ('MODEL','DEVICE')),policy_key VARCHAR(255) NOT NULL,
 minimum_per_day NUMERIC(20,4) NOT NULL CHECK(minimum_per_day>=0),maximum_per_day NUMERIC(20,4) NOT NULL,
 version BIGINT NOT NULL CHECK(version>0),actor VARCHAR(255) NOT NULL,updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(scope,policy_key),CHECK(maximum_per_day>=minimum_per_day)
);
CREATE TABLE reminders(contract_id UUID NOT NULL REFERENCES contracts(contract_id),period DATE NOT NULL,kind VARCHAR(20) NOT NULL,
 PRIMARY KEY(contract_id,period,kind));
