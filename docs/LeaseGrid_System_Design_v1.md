**LEASEGRID  /  ENGINEERING DESIGN** 

_SYSTEM DESIGN DOCUMENT_ 

# **LeaseGrid** 

_Distributed resource allocation and lease management platform_ 

_Architecture  •  Data model  •  API contracts  •  Concurrency  •  Reliability_ 

|**Document metadata**|**Value**|
|---|---|
|Version|1.0 — proposed design|
|Date|19 November 2026|
|Audience|Developer / reviewer / system-design interviewer|
|Implementation priority|P0 transactional MVP → P1 distributed scheduling → P2 integrations|
|Status|Design baseline; decisions may evolve after benchmarks|



Design principle: correctness first, reliability second, measured performance third. 

LEASEGRID  •  SYSTEM DESIGN  |  1 

**LEASEGRID  /  ENGINEERING DESIGN** 

## **1. Executive overview** 

LeaseGrid coordinates temporary access to scarce, shared computing resources. A team requests N GPU or compute slots for a bounded duration. LeaseGrid checks permissions, team quota and pool capacity, then atomically creates a timelimited lease. When the lease ends, the capacity becomes available for future requests. 

The first release manages logical capacity, not real GPU hardware. A later compute-agent integration will turn logical leases into enforceable access to individual machines or GPUs. 

### **1.1 Decisions at a glance** 

|**Decision**|**Selected design**|**Reason**|
|---|---|---|
|Architecture|Modular Spring Boot monolith; 1 instance in P0, 2+ in P1|Minimize deployment complexity while<br>proving distributed correctness|
|Consistency|PostgreSQL is the only authority for capacity, quotas and<br>leases|Atomic transactions preserve invariants|
|Capacity model|Fungible integer slots per resource pool|Simple, testable MVP; specific devices later|
|Concurrency|Row locks in a fixed order; READ COMMITTED transactions|Correct allocation without a distributed lock<br>service|
|Lease recovery|Scheduled PostgreSQL-backed sweeper|Recover capacity after abandoned leases|
|Queue|None in P0; durable strict FIFO per pool in P1|Preserve a small correct first scope|
|Redis|Optional cache / API throttling, not source of truth|Avoid split-brain allocation decisions|



### **1.2 Scope and assumptions** 

- One organization with multiple teams; ADMIN and MEMBER roles. 

- Each resource pool represents interchangeable units (example: 10 logical A100 slots). 

- Team quotas are per pool and bound concurrent allocation; usage budgets are out of scope. 

- A single allocation request targets one pool; multi-pool atomic bundles are deferred. 

- Resource availability means database reservation availability, not hardware readiness. 

- P0 clients receive a result immediately; P1 may queue requests if capacity is unavailable. 

### **1.3 Goals and non-goals** 

|**Goals**|**Explicit non-goals for P0**|
|---|---|
|Never exceed committed pool capacity or team quota|No physical GPU provisioning or job execution|
|Crash-safe acquire / renew / release / expire|No microservices, Kafka or Kubernetes|
|Recover abandoned leases and deduplicate retries|No cross-region active-active operation|
|Benchmark p95 latency and concurrency correctness|No sophisticated scheduler or billing system|



LEASEGRID  •  SYSTEM DESIGN  |  2 

**LEASEGRID  /  ENGINEERING DESIGN** 

## **2. High-level architecture** 



<!-- Start of picture text -->
React dashboard / API clients<br>Load balancer / API ingress<br>Spring Boot instance A Spring Boot instance B<br>. >. ae<br>Redis (optional) PostgreSQL (authoritative) Monitoring stack<br>Scheduled workers<br><!-- End of picture text -->

_Figure 1 — Deployment view. Worker jobs are initially part of the same Spring Boot application._ 

### **2.1 Responsibilities by component** 

|**Component**|**Responsibilities**|
|---|---|
|REST controllers|Validate API shape, authenticate, authorize, translate domain errors to HTTP|
|AllocationService|Transactional check-and-reserve, quota enforcement, lease and idempotency persistence|
|LeaseService|Read lease, renew, release; enforce status transition rules|
|ExpiryWorker|Find overdue leases; reclaim in bounded transactional batches|
|QueueScheduler (P1)|Order waiting requests by pool; grant head requests transactionally|
|PostgreSQL|Single durable source of truth: resource capacity, quota counters, leases, idempotency|
|Redis (optional)|Cache non-authoritative queries; rate-limit incoming APIs|
|Monitoring|Request/DB/worker metrics, traces, logs and dashboards|



### **2.2 Deployment and scaling** 

P0 runs one Spring Boot API + worker process and PostgreSQL. P1 runs two or more identical, stateless Spring Boot instances behind a reverse proxy, all sharing PostgreSQL. Local instance memory is never authoritative. Workers can run in every instance because database locks and state predicates make operations mutually safe. 

A single PostgreSQL primary remains a dependency; this design is fault tolerant to application-instance crashes, not to an unavailable primary database. Database backups, point-in-time recovery, and failover are operational follow-ups. 

LEASEGRID  •  SYSTEM DESIGN  |  3 

**LEASEGRID  /  ENGINEERING DESIGN** 

## **3. Domain model and data ownership** 



<!-- Start of picture text -->
teams<br>—<br><!-- End of picture text -->

_Figure 2 — Conceptual entities. Core tables and important constraints are defined below._ 

### **3.1 Core tables (P0)** 

|**Table**|**Essential columns / invariants**|
|---|---|
|users|id UUID PK, email UNIQUE, password_hash, created_at|
|teams|id UUID PK, name, created_at|
|team_memberships|(user_id, team_id) UNIQUE; role ADMIN/MEMBER; foreign keys to both|
|resource_pools|id UUID PK, name, type, total_capacity > 0, allocated_capacity >= 0, status, max_lease_seconds|
|team_quotas|(team_id, pool_id) UNIQUE; quota_limit >= 0, used_units >= 0|
|leases|id UUID PK, pool_id, team_id, owner_id, quantity > 0, status, issued_at, expires_at, released_at|
|idempotency_records|(caller_id, key) UNIQUE; request_hash, lease_id, created_at, expires_at|



Create DB CHECK constraints: allocated_capacity <= total_capacity; team_quotas.used_units <= team_quotas.quota_limit; leases.expires_at > leases.issued_at. Disable destructive deletion of referenced pools and teams. Foreign keys are mandatory. 

### **3.2 Cardinalities** 

- A user joins multiple teams through team_memberships; a team has multiple users. 

- A team may have one quota per pool; a pool has many team quota rows. 

- A lease belongs to exactly one pool, one team and one requesting owner. 

- Idempotency records belong to one caller and point to the resulting lease. 

- P1 allocation_requests refer to a team/pool and optionally the lease created when GRANTED. 

### **3.3 Indexes** 

```
CREATE UNIQUE INDEX ux_quota_team_pool ON team_quotas(team_id, pool_id);
CREATE INDEX ix_leases_expiry ON leases(expires_at)
  WHERE status = 'ACTIVE';
CREATE INDEX ix_leases_owner ON leases(owner_id, issued_at DESC);
CREATE UNIQUE INDEX ux_idem_caller_key
```

LEASEGRID  •  SYSTEM DESIGN  |  4 

**LEASEGRID  /  ENGINEERING DESIGN** 

```
  ON idempotency_records(caller_id, idem_key);
```

Use Flyway migrations from the first commit. Aggregate counters are maintained transactionally for fast reads; periodic reconciliation compares counters with active lease totals and reports discrepancies rather than silently rewriting history. 

## **4. The most important design: atomic allocation** 

### **4.1 Non-negotiable invariants** 

```
0 <= resource_pools.allocated_capacity <= resource_pools.total_capacity
0 <= team_quotas.used_units <= team_quotas.quota_limit
successful allocation => exactly one ACTIVE lease and matching counter deltas
release / expiry / revoke => counter deltas applied at most once
```

### **4.2 Lock order and isolation** 

Use a PostgreSQL READ COMMITTED transaction and lock rows in the same order for every capacity-changing operation: (1) resource_pool, (2) team_quota, (3) lease if one exists. Renewals change no counters and can lock only the lease. When releasing a known lease, read its identifying pool/team IDs first without a lock, acquire the pool and quota locks, then lock and revalidate the lease state. This reduces deadlock risk. 

A transaction must re-check pool status, capacity, quota and lease status after acquiring the relevant locks. A pretransaction availability check is only a UI hint and cannot authorize an allocation. 

### **4.3 Acquisition sequence** 



<!-- Start of picture text -->
Client API / Service PostgreSQL Result<br>POST /allocations<br>_OT——EA<br>locked rows<br>es+Fare current countscos<br>validate; insert lease; update counts<br>EE*<br>en<br>———_201 + lease 1D<br><!-- End of picture text -->

_Figure 3 — Simplified successful allocation transaction. Idempotency is checked inside the same transaction._ 

1.  Authenticate and authorize the team; validate positive quantity and allowed duration. 

2.  Start a DB transaction; claim an idempotency key scoped to the authenticated caller. 

3.  Lock the pool row, then the (team, pool) quota row with SELECT ... FOR UPDATE. 

4.  Verify pool status ACTIVE, pool free capacity >= requested quantity, and unused team quota >= quantity. 

5.  Increment pool.allocated_capacity and quota.used_units; insert ACTIVE lease with DB-computed expiry. 

6.  Associate the idempotency record with the lease; commit; return 201 with lease ID and expiry. 

LEASEGRID  •  SYSTEM DESIGN  |  5 

**LEASEGRID  /  ENGINEERING DESIGN** 

### **4.4 Illustrative SQL inside one transaction** 

```
BEGIN;
SELECT * FROM resource_pools WHERE id = :pool_id FOR UPDATE;
SELECT * FROM team_quotas
 WHERE team_id = :team_id AND pool_id = :pool_id FOR UPDATE;
-- Recheck ACTIVE status, free capacity and quota here.
UPDATE resource_pools SET allocated_capacity = allocated_capacity + :q
 WHERE id = :pool_id;
UPDATE team_quotas SET used_units = used_units + :q
 WHERE team_id = :team_id AND pool_id = :pool_id;
INSERT INTO leases(id, pool_id, team_id, owner_id, quantity,
                   status, issued_at, expires_at)
 VALUES (:lease_id, :pool_id, :team_id, :owner_id, :q,
         'ACTIVE', clock_timestamp(), :expires_at);
-- Persist the idempotency key and resulting lease reference.
COMMIT;
```

This illustrates lock and write ordering; production code should use parameterized statements and calculate expiry consistently from database time. The idempotency-key claim and collision handling must be performed as part of this transaction, not after commit. 

### **4.5 Why this prevents double allocation** 

Suppose one unit remains. Client A and Client B request it simultaneously on separate Spring Boot instances. A locks the pool first; B waits. A commits the increment and lease. B then reads the updated pool row under its lock and sees zero free units, so it cannot grant a second lease. No application-local mutex or Redis lock is required. 

## **5. Lifecycle operations and race conditions** 

### **5.1 States** 

|**State**|**Meaning**|**Allowed next states**|
|---|---|---|
|ACTIVE|Capacity is reserved; lease valid until expires_at|RELEASED, EXPIRED, REVOKED (P1)|
|RELEASED|Owner returned capacity|None|
|EXPIRED|Expiration worker reclaimed capacity|None|
|REVOKED|Admin reclaimed capacity (P1)|None|



### **5.2 Renew** 

Authorize owner, lock the lease row, and require status ACTIVE and expires_at > database current time. Compute a new expiry subject to policy and maximum lifetime. Update expires_at and return the result. Renewing does not change capacity or quota counters. If the expiry worker reaches the lease first and transitions it to EXPIRED, renewal fails rather than resurrecting it. 

### **5.3 Release** 

Read pool/team IDs, begin transaction, lock pool then quota then lease, and recheck ACTIVE status. Transition ACTIVE to RELEASED, decrement both counters by quantity, and commit. Repeating a release on an already terminal lease returns its terminal state without subtracting counters again. 

### **5.4 Expire** 

The worker finds candidate ACTIVE leases whose expires_at is before database time. It processes each candidate with the same pool → quota → lease lock order and rechecks both status and deadline. Then it transitions to EXPIRED and subtracts counters exactly once. This is eventually consistent recovery: an overdue lease is invalid immediately by timestamp, but capacity may remain conservatively held until the worker commits reclamation. 

LEASEGRID  •  SYSTEM DESIGN  |  6 

**LEASEGRID  /  ENGINEERING DESIGN** 

### **5.5 Time and scheduling semantics** 

- Use database time for expiry comparisons; do not depend on synchronized application clocks. 

- Lease validity is determined by ACTIVE status AND expires_at > database time. 

- A bounded sweep interval affects recovery delay, not correctness. 

- A client losing its lease must stop using the resource; hard enforcement requires P2 agents. 

### **5.6 Race-case matrix** 

|**Race**|**Required behavior**|
|---|---|
|Acquire vs acquire|One waits on pool row; the second rechecks updated free capacity|
|Release vs expire|Only the first ACTIVE → terminal state subtracts capacity|
|Renew vs expire|Recheck expiry/status under lease lock; no lease resurrection|
|Reduce capacity vs allocate|Admin update locks pool row; cannot shrink below allocated|
|Reduce quota vs allocate|Quota update locks quota row; cannot shrink below used_units|
|API crash before COMMIT|Entire transaction rolls back; no partial reservation|
|API crash after COMMIT|Retry with same idempotency key returns original lease|



## **6. Idempotency and API design** 

### **6.1 Idempotency contract** 

Clients provide Idempotency-Key for POST /allocations. The key is unique within an authenticated caller scope. Store a canonical request hash, resulting lease ID, and retention deadline. For an identical retry, return the existing lease response (including its current status). If the key is reused with a different payload, return 409 IDEMPOTENCY_KEY_REUSED. 

For P0, only committed successful allocations need durable deduplication; failed capacity/validation responses may be retried. Ensure a unique database constraint and transactional claim so concurrent equal-key requests cannot create duplicate leases. Retain the key longer than the documented retry window (initial proposal: 24 hours). 

### **6.2 API endpoints** 

|**Method**|**Path**|**Behavior**|
|---|---|---|
|POST|/api/v1/auth/login|Authenticate; return token|
|POST|/api/v1/pools|ADMIN creates pool|
|GET|/api/v1/pools|List authorized pools / capacity snapshot|
|PATCH|/api/v1/pools/{id}|ADMIN changes capacity / status safely|
|PUT|/api/v1/teams/{id}/quotas/{poolId}|ADMIN configures quota|
|POST|/api/v1/allocations|Create lease; requires Idempotency-Key|
|GET|/api/v1/leases/{id}|Get lease, ownership and expiry|
|POST|/api/v1/leases/{id}/renew|Extend an ACTIVE lease|
|POST|/api/v1/leases/{id}/release|Release exactly once|
|GET|/api/v1/requests/{id} (P1)|Check queue status|



LEASEGRID  •  SYSTEM DESIGN  |  7 

**LEASEGRID  /  ENGINEERING DESIGN** 

### **6.3 Request and response examples** 

```
POST /api/v1/allocations
Authorization: Bearer <access-token>
Idempotency-Key: 5b34c3d7-721d-4f05-b1f4-b03c76e44e31
{ "poolId": "c70d...", "teamId": "51a2...",
  "quantity": 2, "durationSeconds": 1800 }
```

```
HTTP/1.1 201 Created
{ "leaseId": "9f73...", "status": "ACTIVE",
  "quantity": 2, "expiresAt": "2026-11-19T17:30:00Z" }
```

|**HTTP**|**Error code**|**Meaning**|
|---|---|---|
|400|INVALID_REQUEST|Bad quantity, duration or malformed JSON|
|401 / 403|UNAUTHENTICATED / FORBIDDEN|Identity absent or insufficient permission|
|404|POOL_NOT_FOUND / LEASE_NOT_FOUND|Entity absent or hidden by authorization|
|409|CAPACITY_EXHAUSTED|Insufficient pool capacity|
|409|QUOTA_EXCEEDED|Concurrent team quota insufficient|
|409|LEASE_NOT_ACTIVE|Renewal or state transition invalid|
|409|IDEMPOTENCY_KEY_REUSED|Key reused with mismatched payload|
|429|RATE_LIMITED|API abuse protection; unrelated to resource capacity|



### **6.4 Security boundaries** 

JWT identity is used for caller authentication. Resource authorization uses persisted team memberships and roles, not trust in a client-supplied team ID. ADMIN actions require administrative role and appropriate scope; MEMBERs can operate only their own leases unless team policy permits more. Credentials, idempotency keys and tokens must not appear in plain-text logs. 

## **7. P1 scheduler and distributed behavior** 

### **7.1 Persistent queue schema** 

Add allocation_requests(id, caller_id, pool_id, team_id, quantity, duration_seconds, state, created_at, granted_lease_id, deadline_at). Index by (pool_id, state, created_at, id). States: QUEUED → GRANTED / CANCELLED / TIMED_OUT. Granting a request creates a lease in the same transaction as the state change. 

### **7.2 Strict FIFO per pool** 

When any QUEUED request exists for a pool, new allocations join that pool queue instead of bypassing it. The scheduler locks the pool row and processes the oldest eligible QUEUED request; if the head does not fit, it stops. Multiple workers may run, but locking the pool row serializes scheduling decisions per pool. No SKIP LOCKED is used to skip the head request, because that would violate strict FIFO. 

Tradeoff: a large head request can block smaller requests even when some capacity is free. P2 may introduce documented backfilling or weighted-fair policies with different semantics. 

### **7.3 Coordination approach** 

- Every API instance uses the same database and identical transaction rules. 

- Workers use batch limits, short transactions, retries with backoff, and safe state transitions. 

- Do not rely on @Scheduled running only once globally; safe duplicate execution is required. 

- SKIP LOCKED is suitable for independent queue-like work, but not for strict FIFO head ordering. 

LEASEGRID  •  SYSTEM DESIGN  |  8 

**LEASEGRID  /  ENGINEERING DESIGN** 

### **7.4 P2 resource-level enforcement** 

Individual GPU units and compute agents introduce a new correctness requirement: a stale worker may continue after its lease expires. Give each reallocation of an exclusive resource a monotonically increasing fencing token; the agent must reject commands with tokens older than the highest accepted token. A fencing token is useful only when the resource/agent checks it. 

## **8. Reliability, operations and observability** 

### **8.1 Failure behavior** 

|**Failure**|**Expected behavior / caveat**|
|---|---|
|App instance restarts|Other instances continue; in-flight DB transaction rolls back if uncommitted|
|Client loses network|Idempotency retry retrieves the original committed lease|
|Expiry worker crashes|Next invocation picks up overdue ACTIVE leases; no double reclaim|
|Database temporarily unavailable|Return retryable service error; do not invent a successful allocation|
|Redis unavailable|Core allocation remains correct; optional cache / rate limit may degrade|
|Clock skew among app nodes|DB time controls lease validity and expiry checks|
|External GPU job keeps running|Logical lease can expire; real shutdown requires compute agent (P2)|



### **8.2 Monitoring** 

- Counters: allocation attempts by outcome, lease releases, expirations, worker errors. 

- Histograms: allocation latency, DB lock wait, expiry recovery lag and P1 queue waiting time. 

- Gauges: pool capacity/allocated, active leases, queued requests, app/DB pool health. 

- Logs: request correlation ID, actor scope, lease ID and error code (without secrets). 

- Alerts: high expiry lag, stuck queues, persistent contention and counter reconciliation mismatches. 

Avoid using high-cardinality values (user ID or lease ID) as Prometheus metric labels. Use logs or tracing for individualrequest correlation. 

### **8.3 Benchmark gates** 

|**Benchmark**|**Setup**|**Pass / measure**|
|---|---|---|
|Capacity invariant|Pool 10; 100+ overlapping 1-slot requests|At most 10 ACTIVE unit grants; no counter drift|
|Quota invariant|Team quota 4; 100 simultaneous requests|No accepted team usage > 4|
|Idempotency|100 concurrent requests with same key|Exactly one lease created|
|Release vs expiry|Race identical lease operations|Exactly one decrement|
|Restart recovery|Stop/restart app with expired ACTIVE leases|Eventually reclaimed; no double decrement|
|Cross-instance (P1)|Two Spring Boot instances, same PostgreSQL|Same invariants preserved|
|Performance|Fixed k6 workload and dataset|Report throughput; p50/p95/p99; before/after|



The initial target of p95 < 300 ms under a specified test workload is aspirational. Any resume improvement must use measured, reproducible before/after results; correctness must not be traded for speed. 

LEASEGRID  •  SYSTEM DESIGN  |  9 

**LEASEGRID  /  ENGINEERING DESIGN** 

## **9. Implementation map and milestones** 

### **9.1 Suggested code organization** 

```
com.leasegrid
  auth/          controller, service, security, domain
  team/          membership and authorization
  resource/      pool management and policy
  quota/         quota management and validation
  allocation/    transactional AllocationService
  lease/         renew, release, state transitions
  idempotency/   request deduplication
  worker/        expiry recovery (P0)
  scheduling/    persistent FIFO queue (P1)
  metrics/       operational instrumentation
  common/        errors, API responses, configuration
```

### **9.2 Concrete build order** 

1.  Foundation: project structure, Docker Compose PostgreSQL, Flyway, login, teams and resource-pool CRUD. 

2.  Correctness core: quotas, atomic AllocationService, lease creation, release and renewal, DB CHECK constraints. 

3.  Reliability: idempotency key store, expiry worker, race tests with Testcontainers. 

4.  Proof: k6 concurrency script, benchmark baseline, query/lock profiling and targeted optimization. 

5.  P1: persistent queue, strict FIFO scheduler, multiple app instances, monitoring dashboard, audit events. 

6.  P2: individual resource agents, fencing, advanced fairness and Kubernetes as justified. 

### **9.3 Definition of done for P0** 

- A user can acquire, read, renew and release a logical lease through documented APIs. 

- Quota and pool capacity are never exceeded in repeatable concurrent integration tests. 

- Expired or abandoned leases are reclaimed safely after application restart. 

- Duplicate allocation requests are deduplicated with a durable key. 

- Core flows include unit and database integration tests, migration scripts and a local start guide. 

- One benchmark report includes test environment, load script, throughput and latency distributions. 

## **10. Tradeoffs and open design decisions** 

|**Question**|**Decision for v1 / follow-up**|
|---|---|
|Why not microservices?|A modular monolith minimizes distributed failure surfaces while preserving module<br>boundaries.|
|Why row locks instead of Redis locks?|The state and counters live in PostgreSQL; local DB transactions offer one atomic<br>consistency boundary.|
|Why counters rather than SUM(leases)?|Fast admission checks; keep consistency via transactions, constraints and<br>reconciliation.|
|Why logical slots instead of physical GPUs?|Validates scheduler correctness without requiring expensive infrastructure.|
|What about high write contention?|Benchmark first; consider conditional updates or partitioned pools after profiling.|
|What happens during PostgreSQL outage?|Allocation is unavailable; avoid unsafe optimistic grants.|
|How are long-running jobs stopped?|Not guaranteed by P0 logical leases; P2 enforce through agents + fencing.|
|What about fairness?|Strict FIFO in P1; P2 may use priority, aging, or backfilling explicitly.|



LEASEGRID  •  SYSTEM DESIGN  |  10 

**LEASEGRID  /  ENGINEERING DESIGN** 

## **11. References and design notes** 

Implementation choices are informed by official PostgreSQL SELECT and transaction-isolation documentation, and Spring Framework transaction-management guidance: 

https://www.postgresql.org/docs/current/sql-select.html 

https://www.postgresql.org/docs/current/transaction-iso.html 

https://www.postgresql.org/docs/current/explicit-locking.html 

https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html 

Document version 1.0. Treat all numeric performance targets as unverified targets until benchmark evidence exists. 

LEASEGRID  •  SYSTEM DESIGN  |  11 

