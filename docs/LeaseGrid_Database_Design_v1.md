**LEASEGRID  /  DATABASE DESIGN** 

# Table of Contents 

# **LeaseGrid — Database Design v1.0** 

**Architecture:** PostgreSQL authoritative source of truth; Spring Boot modular monolith **Scope:** P0 transactional leasing; P1 FIFO queue and audit trail **Status:** Implementation blueprint · 19 November 2026 

## **1. Design decisions** 

|**Decision**|**Choice**|**Reason**|
|---|---|---|
|Database|PostgreSQL 16+|ACID transactions, row locks,<br>FK/CHECK constraints, partial<br>indexes|
|IDs|UUID primary keys|Works across multiple Spring Boot<br>instances|
|Time|TIMESTAMPTZ and database clock|Time comparisons do not depend<br>on application-node clock skew|
|||Simple GPU/CPU slot allocation in|
|Inventory|Fungible integer units per pool|P0; individual physical units<br>deferred|
|Pool availability|total_capacity - allocated_capacity|Avoid independently storing a<br>redundant available counter|
|Quota usage|team_quotas.used_units|Atomic team-level admission<br>control|
|Isolation|READ COMMITTED with explicit<br>row locks|Correct when**all**capacity-<br>changing paths follow lock order|
|Lock order|pool → quota → lease|Avoid lock-order inversion between<br>acquire/release/expiry|
|Expiration|Lease status + timestamp +<br>sweeper|Pending expiry may delay reuse,<br>never over-allocate|
|Queue|Strict FIFO per pool (P1)|Predictable fairness, deliberate<br>head-of-line blocking|
|Redis|Non-authoritative optional cache|A Redis lock alone must not grant<br>ownership|



**Core invariant:** Every allocation must be authorized, capacity-backed, quota-backed, and committed atomically with its lease. A free-capacity number shown on the dashboard is advisory, never permission to allocate. 

LEASEGRID  •  v1.0     |     1 

**LEASEGRID  /  DATABASE DESIGN** 

## **2. Entity relationship diagram** 



<!-- Start of picture text -->
PK id : UUID » PK id : UID<br>UNIQUE(caller_id, idem_key) FK pool_id / team_id / owner_id<br>request_hash / response_body quantity, status<br>FK lease_id > leases issued_at/ expires_at<br>PK (team_id, user_id) PK (team _id, pool_id)<br>FK team_id > teams quota_limit<br>FK user_id > users used_units<br>team_role |<br>resource_pools<br>teams<br>PK id :UUID PK id : UUID PK id :UUID<br>email (case-insensitive unique) — name total_capacity<br>system_role, status FK created_by > users allocated_capacity<br>status / max_lease_seconds<br><!-- End of picture text -->

##### **Cardinalities:** 

- One user belongs to many teams via team_memberships; one team has many members. 

- One resource pool can have many team quotas; each (team_id, pool_id) has exactly one quota row. 

- One user, team, and resource pool can be linked to many leases; each lease references one of each. 

- One user can have many idempotency records. A completed allocation record may reference one resulting lease. 

- Team quotas are also the parent of leases through a composite FK, so a lease cannot reference a nonexistent team-pool quota policy. 

### **What we deliberately do not model in P0** 

The resource_pools table describes **fungible quantities** , for example 10 interchangeable GPU slots. It does not assign GPU serial numbers or run workloads. Individual GPUs, machine agents, and fencing tokens belong in a later physical-resource design. 

## **3. P0 schema — table-by-table** 

### **3.1 users** 

|**Column**|**Type**|**Notes**|
|---|---|---|
|id|UUID|Primary key|
|email|VARCHAR(320)|Unique case-insensitively using<br>index on lower(email)|
|display_name|VARCHAR(120)|Required|
|password_hash|VARCHAR(255)|Never store plaintext|
|system_role|VARCHAR(16)|ADMIN or MEMBER|



LEASEGRID  •  v1.0     |     2 

**LEASEGRID  /  DATABASE DESIGN** 

|**Column**|**Type**|**Notes**|
|---|---|---|
|account_status|VARCHAR(16)|ACTIVE or SUSPENDED|
|created_at, updated_at|TIMESTAMPTZ|Audit timestamps|



**Rule:** Authentication/authorization is enforced by the service; a user referenced by a historical lease cannot simply be deleted. 

### **3.2 teams** 

id (PK), name (unique), created_by (FK → users.id), created_at, updated_at. 

**Rule:** Team name is organization-wide unique in the single-organization MVP. Multi-tenant scoping is a later change. 

### **3.3 team_memberships** 

team_id (FK), user_id (FK), team_role (OWNER, ADMIN, MEMBER), joined_at. 

**Primary key:** (team_id, user_id) — the same user cannot be added to the same team twice. This implements a many-to-many relationship. 

### **3.4 resource_pools** 

|**Column**|**Type**|**Notes**|
|---|---|---|
|id|UUID|Primary key|
|code|VARCHAR(80)|Unique stable reference, e.g. gpu-<br>a100|
|name|VARCHAR(160)|Human-readable name|
|resource_type|VARCHAR(32)|GPU / CPU_SLOT / TEST_ENV /<br>GENERIC|
|total_capacity|INTEGER|Greater than zero|
|allocated_capacity|INTEGER|Defaults to zero|
|max_lease_seconds|INTEGER|60 through 604800|
|status|VARCHAR(16)|ACTIVE / DRAINING / DISABLED|
|created_at, updated_at|TIMESTAMPTZ|Timestamps|



**Constraints:** 0 <= allocated_capacity <= total_capacity. New acquisitions require status = 'ACTIVE'. DRAINING/DISABLED does not invalidate existing leases. 

**Computed capacity:** available_capacity = total_capacity - allocated_capacity. 

### **3.5 team_quotas** 

|**Column**|**Type**|**Notes**|
|---|---|---|
|team_id|UUID|Composite PK, FK → teams|
|pool_id|UUID|Composite PK, FK →<br>resource_pools|
|quota_limit|INTEGER|Maximum concurrent allocated<br>units|
|used_units|INTEGER|Currently reserved units|
|updated_at|TIMESTAMPTZ|Last quota update|



LEASEGRID  •  v1.0     |     3 

**LEASEGRID  /  DATABASE DESIGN** 

**Constraints:** 0 <= used_units <= quota_limit. Quota policy changes must lock the same quota row used by allocations, rejecting a reduction below used_units. 

### **3.6 leases** 

|**Column**|**Type**|**Notes**|
|---|---|---|
|id|UUID|Primary key|
|pool_id|UUID|FK → resource_pools|
|team_id|UUID|FK → teams|
|owner_id|UUID|FK → users|
|quantity|INTEGER|Positive number of units|
|status|VARCHAR(16)|ACTIVE / RELEASED / EXPIRED /<br>REVOKED|
|issued_at|TIMESTAMPTZ|Time of successful allocation|
|expires_at|TIMESTAMPTZ|Lease deadline|
|ended_at|TIMESTAMPTZ|Terminal-state timestamp,<br>nullable while ACTIVE|
|renew_count|INTEGER|Number of renewals|



**Rules:** expires_at > issued_at; a terminal lease must have ended_at. Composite FK (team_id, pool_id) references team_quotas to ensure a policy exists. 

**Subtlety:** A lease may still be stored with status ACTIVE for a short period after expires_at while the sweeper is pending. Its **validity** is status='ACTIVE' AND expires_at > database current time, whereas its **reserved capacity** remains counted until a terminal-state transaction reclaims it. This is conservative and never over-allocates. 

### **3.7 idempotency_records** 

id (PK), caller_id (FK), idem_key, operation, request_hash, record_status, lease_id (nullable FK), http_status, response_body (JSONB), created_at, expires_at. 

**Unique:** (caller_id, idem_key). A key replay with the same normalized request returns the stored response. A key replay with a different payload is rejected. 

**Transaction rule:** Claim key and finalize result **within the same allocation transaction** ; do not commit an 

IN_PROGRESS record as a permanent state. Business conflicts can be stored as completed conflict responses without allocating capacity. For transport failures before commit, retry safely. 

**Retention:** Define retention explicitly (e.g. at least 24 hours) and document that reuse after cleanup constitutes a new operation. 

## **4. Atomic acquisition algorithm** 

For one request to lease q units from pool p by team t: 

1. Authorize the caller and validate q > 0 and requested duration. 

2. Begin a single database transaction; claim idempotency key or replay an existing committed response. 

3. Lock the resource_pools row for p using SELECT ... FOR UPDATE. 

4. Lock team_quotas row (t, p) using SELECT ... FOR UPDATE. 

LEASEGRID  •  v1.0     |     4 

**LEASEGRID  /  DATABASE DESIGN** 

5. After acquiring locks, re-read/validate pool status, total_capacity - allocated_capacity >= q, quota_limit - used_units >= q, and max duration. 

6. Increment allocated_capacity and used_units in the transaction. Verify exactly one row updated in each table. 

7. Insert an ACTIVE lease with issued_at and expires_at computed using database time. 

8. Persist idempotency response and commit. 

**Every write in steps 6–7 commits together or rolls back together.** Row locking serializes conflicting transactions for 

the same pool and quota. PostgreSQL's READ COMMITTED with explicit row locks supports this pattern when all relevant mutation paths honor the protocol. 

**Do not use** synchronized, an in-memory map, or Redis as the authoritative allocation lock. Such mechanisms do not protect across independent JVM instances without further assumptions. 

## **5. Lifecycle and race safety** 

|**Operation**|**Lock order**|**State/counter effect**|
|---|---|---|
|Acquire|idempotency claim → pool → quota|Increase both counters and insert<br>ACTIVE lease|
|Release|read lease metadata → pool →<br>quota → lease|First ACTIVE→RELEASED<br>decreases both counters|
|Expire|read candidate metadata → pool →<br>quota → lease|First overdue ACTIVE→EXPIRED<br>decreases counters|
|Revoke (P1)|read lease metadata → pool →<br>quota → lease|First ACTIVE→REVOKED decreases<br>counters|
|Renew|pool → lease|If ACTIVE and not expired, extend<br>expires_at; counters unchanged|
|Change pool capacity|pool|Reject totals below allocated<br>capacity|
|Change team quota|quota (or pool→quota when<br>combined)|Reject limit below used units|



- **Release vs expire:** only one transition from ACTIVE to terminal state wins and decrements counters. The other observes terminal state and does not decrement. 

- **Renew vs expire:** lock and recheck status + deadline using clock_timestamp() **after** any lock wait. Renewal cannot resurrect an expired lease. 

- **Release after timeout but before sweep:** transition to EXPIRED and reclaim capacity rather than granting a fresh release to an already invalid lease. 

- **Worker failure:** expired ACTIVE leases remain discoverable and can be processed on the next sweep. 

- **Multiple workers:** repeat discoveries are harmless, because workers recheck state inside the locked transaction. 

**Candidate sweep query** (no claim lock in the discovery step): 

#### **SELECT id FROM** leases 

**WHERE** status = 'ACTIVE' **AND** expires_at <= clock_timestamp() **ORDER BY** expires_at, **id** 

#### **LIMIT** 100; 

Each ID is reclaimed in a **short, independent transaction** using the shared lock order. This approach is simple for P0. Batching and claiming can be optimized in P1 without breaking locking discipline. 

LEASEGRID  •  v1.0     |     5 

**LEASEGRID  /  DATABASE DESIGN** 

## **6. P1 schema — scheduling and audit** 



<!-- Start of picture text -->
audit_events<br>Append-only history<br>PP ¥ ” ctorid——*_ users<br>callerid<br>—<br>_<br>leases id (unig allocation_requests<br>Optional request_id- request id (unique) Queued / granteda / cancelled poolid | Pesourcespools<br>team, poo!<br>team_quotas<br><!-- End of picture text -->

### **6.1 allocation_requests** 

Required columns: id, pool_id, team_id, caller_id, quantity, duration_seconds, status, created_at, updated_at, deadline_at, queue_if_unavailable, and optional rejection_code. 

States: QUEUED, GRANTED, REJECTED, CANCELLED, TIMED_OUT. A granted request links to a lease through **unique** leases.request_id. 

**Strict FIFO:** New requests cannot bypass an existing queue in that pool. Workers lock the **pool row first** , then select the oldest queued request in creation order and attempt to grant it. If that request cannot fit, the scheduler waits for more capacity. Do not SKIP LOCKED across queue heads if strict FIFO semantics are required; skipping blocked head items can violate ordering. 

### **6.2 audit_events** 

id (BIGINT identity PK), actor_id (nullable FK), action, entity_type, entity_id, details (JSONB), occurred_at. 

Record creation/modification of pools, quota changes, lease transitions, revocations, and key administrative actions. Do not log plaintext secrets or raw authentication tokens. 

## **7. Index strategy** 

|**Index**|**Why**|
|---|---|
|UNIQUE lower(users.email)|Case-insensitive account lookup/uniqueness|
|PK team_quotas(team_id,pool_id)|Exact quota lock and lookup|
|leases(expires_at,id) WHERE status='ACTIVE'|Expiration sweep|
|leases(owner_id,issued_at DESC)|Developer lease listing|
|leases(team_id,pool_id) WHERE status='ACTIVE'|Team quota diagnostics|
|UNIQUE idempotency_records(caller_id,idem_key)|Duplicate request safety|
|allocation_requests(pool_id,created_at,id) WHERE<br>status='QUEUED'|FIFO queue head retrieval|
|audit_events(entity_type,entity_id,occurred_at DESC)|Entity history|



Add or remove secondary indexes only after examining actual query plans. Every extra index also adds write overhead. 

## **8. Example — 10 GPUs and two teams** 

|**Table**|**Illustrative row**|
|---|---|
|resource_pools|gpu-a100, total_capacity=10, allocated_capacity=7|



LEASEGRID  •  v1.0     |     6 

**LEASEGRID  /  DATABASE DESIGN** 

|**Table**|**Illustrative row**|
|---|---|
|team_quotas|Alpha/GPU: quota_limit=4, used_units=3|
|team_quotas|Beta/GPU: quota_limit=5, used_units=4|
|leases|Active leases across teams total 7 reserved units|



If Alpha requests two more units, it fails quota validation even though three GPU units are free. If Alpha requests one unit, it may succeed and the pool counter changes to 8 while Alpha's usage changes to 4 — atomically. 

## **9. Why constraints are necessary but not sufficient** 

CHECK (allocated_capacity <= total_capacity) protects **one row** of resource_pools. It cannot by itself ensure that the counter equals the sum of ACTIVE lease quantities, because that is a cross-table property. Similarly, the 

quota CHECK constraint cannot establish that used_units equals the corresponding sum across leases. 

Therefore LeaseGrid must use **shared transactional write paths** and regular read-only reconciliation tests. The SQL package includes an example reconciliation query. 

## **10. Testing and implementation sequence** 

1. Apply V1__core_schema.sql using Flyway. 

2. Implement JPA mappings for users, teams, memberships, pools, quotas and leases. Use composite identifiers or @EmbeddedId for (team_id,pool_id) quotas. 

3. Build AllocationService with @Transactional, explicit lock queries, and one atomic commit. 

4. Test pool=10 against at least 100 concurrent requests, proving accepted unit count never exceeds 10. 

5. Race release, renewal, and expiry against each other; verify **exactly-once capacity reclamation** . 

6. Simulate duplicate keys from concurrent clients; verify only one lease. 

7. Run multiple Spring Boot instances against the same PostgreSQL database and rerun tests. 

8. Apply V2__queue_and_audit.sql to add FIFO queue and audit logging. 

**Recommended package structure:** auth, team, pool, quota, allocation, lease, idempotency, worker 

(plus scheduler and audit in P1). Each package has controller, service, repository, entity, and DTO classes as appropriate. 

## **11. Known tradeoffs and future extensions** 

- **Hot pool row contention:** each request locks its pool row. This favors correctness, but throughput for one busy pool is limited; benchmark before optimizing. 

- **Counter storage:** faster than repeated SUM queries, but demands disciplined transactional updates and reconciliation. 

- **P0 logical slots:** not proof of physical GPU isolation; add individual resource_units, compute agents, and fencing tokens when implementing real infrastructure. 

- **Single primary PostgreSQL:** deliberately avoids cross-region active-active allocation complexity. 

- **Strict FIFO:** predictable, but introduces head-of-line blocking; backfilling or fair scheduling belongs in a separate policy version. 

## **12. File guide** 

- V1__core_schema.sql — directly usable MVP schema migration. 

- V2__queue_and_audit.sql — phase-2 schema migration. 

- transaction_examples.sql — illustrative transaction and reconciliation logic (not a migration). 

LEASEGRID  •  v1.0     |     7 

**LEASEGRID  /  DATABASE DESIGN** 

- erd_core.png, erd_p1.png — database relationship diagrams. 

**Official PostgreSQL references:** Explicit locking, Transaction isolation, Constraints, and SELECT / SKIP LOCKED. 

LEASEGRID  •  v1.0     |     8 

