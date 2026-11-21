CREATE TABLE users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), email varchar(320) NOT NULL,
 display_name varchar(120) NOT NULL, password_hash varchar(255) NOT NULL,
 system_role varchar(16) NOT NULL DEFAULT 'MEMBER' CHECK(system_role IN ('ADMIN','MEMBER')),
 account_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(account_status IN ('ACTIVE','SUSPENDED')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email ON users(lower(email));
CREATE TABLE teams (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name varchar(160) UNIQUE NOT NULL,
 created_by uuid NOT NULL REFERENCES users, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE team_memberships (
 team_id uuid REFERENCES teams, user_id uuid REFERENCES users,
 team_role varchar(16) NOT NULL CHECK(team_role IN ('OWNER','ADMIN','MEMBER')),
 joined_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(team_id,user_id)
);
CREATE TABLE resource_pools (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), code varchar(80) UNIQUE NOT NULL, name varchar(160) NOT NULL,
 resource_type varchar(32) NOT NULL CHECK(resource_type IN ('GPU','CPU_SLOT','TEST_ENV','GENERIC')),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DRAINING','DISABLED')),
 total_capacity integer NOT NULL CHECK(total_capacity > 0), allocated_capacity integer NOT NULL DEFAULT 0 CHECK(allocated_capacity BETWEEN 0 AND total_capacity),
 max_lease_seconds integer NOT NULL CHECK(max_lease_seconds BETWEEN 60 AND 604800),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE team_quotas (
 team_id uuid REFERENCES teams, pool_id uuid REFERENCES resource_pools, quota_limit integer NOT NULL,
 used_units integer NOT NULL DEFAULT 0 CHECK(used_units BETWEEN 0 AND quota_limit),
 updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(team_id,pool_id)
);
CREATE TABLE leases (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), team_id uuid NOT NULL, pool_id uuid NOT NULL, owner_id uuid NOT NULL REFERENCES users,
 quantity integer NOT NULL CHECK(quantity > 0), status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','EXPIRED','RELEASED','REVOKED')),
 issued_at timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK(expires_at > issued_at),
 ended_at timestamptz, renew_count integer NOT NULL DEFAULT 0 CHECK(renew_count >= 0),
 CHECK((status='ACTIVE') = (ended_at IS NULL)), FOREIGN KEY(team_id,pool_id) REFERENCES team_quotas
);
CREATE INDEX leases_expiry ON leases(expires_at,id) WHERE status='ACTIVE';
CREATE INDEX leases_owner ON leases(owner_id,issued_at DESC,id DESC);
CREATE TABLE idempotency_records (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), caller_id uuid NOT NULL REFERENCES users, idem_key varchar(255) NOT NULL,
 operation text NOT NULL DEFAULT 'ALLOCATE', request_hash text NOT NULL, record_status text NOT NULL DEFAULT 'IN_PROGRESS',
 lease_id uuid REFERENCES leases, http_status integer, response_body jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL DEFAULT (now()+interval '8 days'), UNIQUE(caller_id,idem_key),
 CHECK((record_status='IN_PROGRESS' AND http_status IS NULL AND response_body IS NULL) OR
 (record_status='COMPLETED' AND http_status IS NOT NULL AND response_body IS NOT NULL))
);
