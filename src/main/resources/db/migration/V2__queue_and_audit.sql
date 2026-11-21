CREATE TABLE allocation_requests (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), caller_id uuid NOT NULL REFERENCES users, team_id uuid NOT NULL, pool_id uuid NOT NULL,
 quantity integer NOT NULL CHECK(quantity>0), duration_seconds integer NOT NULL CHECK(duration_seconds BETWEEN 60 AND 604800),
 state text NOT NULL CHECK(state IN ('QUEUED','GRANTED','CANCELLED','TIMED_OUT','REJECTED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), deadline_at timestamptz,
 granted_lease_id uuid UNIQUE REFERENCES leases, CHECK((state='GRANTED')=(granted_lease_id IS NOT NULL)),
 FOREIGN KEY(team_id,pool_id) REFERENCES team_quotas
);
CREATE INDEX allocation_fifo ON allocation_requests(pool_id,created_at,id) WHERE state='QUEUED';
ALTER TABLE leases ADD COLUMN request_id uuid UNIQUE REFERENCES allocation_requests;
CREATE TABLE audit_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 actor_id uuid REFERENCES users, event_type text NOT NULL, entity_type text NOT NULL, entity_id uuid NOT NULL,
 details jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_recent ON audit_events(occurred_at DESC,id DESC);
