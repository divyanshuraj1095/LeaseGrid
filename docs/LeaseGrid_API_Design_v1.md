**LEASEGRID  /  API DESIGN SYSTEM** 

# **LEASEGRID** 

## REST API DESIGN & CONTRACT 

A build-ready API blueprint for the distributed resource allocation and lease-management platform. 

|**DOCUMENT**|**VALUE**|
|---|---|
|Version|1.0 — Proposed contract|
|Date|19 November 2026|
|Base URL<br>i|http://localhost:8080/api/v1|
|API specification|OpenAPI 3.1 • LeaseGrid_OpenAPI_v1.yaml|
|Implementation|Java 21 • SpringBoot • PostgreSQL • JWT<br>i|
|Release strategy|P0 MVP first; P1queue, distributed operations and auditinglater|



Contract status: Proposed, aligned with LeaseGrid PRD v1 and PostgreSQL V1/V2 schema. No endpoints are claimed as implemented yet. 

#### **Reading guide** 

This document explains behavioral guarantees and implementation choices. The accompanying OpenAPI YAML is the machine-readable source for exact request/response fields, paths and status codes. The Postman collection provides executable request examples once the backend exists. 

LeaseGrid • API v1.0   |   1 

**LEASEGRID  /  API DESIGN SYSTEM** 

### **1. API boundary and design principles** 

LeaseGrid exposes a versioned JSON REST API for developers, administrators, React UI clients and external applications. The API manages logical, fungible resource units (such as 10 GPU slots); it does not provision or forcibly stop physical GPUs until a later compute-agent integration. 

|**RULE**|**DECISION**|
|---|---|
|Versioning|All business endpoints are under /api/v1; breakingchangesgo to /api/v2.|
|Representation|JSON request and success bodies; camelCase DTOs; UUID IDs; UTC ISO-8601 times.|
|Authentication|Authorization: Bearer <JWT> except register/login.<br>i|
|Error format|application/problem+json, with stable code and traceId fields.|
|Concurrency|PostgreSQL transaction is the allocation authority; Redis nevergrants leases.|
|Pagination|limit 1–100(default 20), opaque cursor, stable ordering.|
|Release scoping|P0 endpoints require V1 schema; P1 requirequeue/audit V2 schema.|



### **2. Resource and authorization model** 

A system ADMIN configures teams, pools and quotas. Members can request allocations only on teams to which they belong. Each lease belongs to one caller (ownerId), one team and one pool. Lease mutations are allowed for the original owner or a system ADMIN in P0. Team membership is checked on every acquisition; never trust teamId just because it appears in the request. 

|**ACTOR**|**AUTHORIZATION**|
|---|---|
|Unauthenticated|Register and login only.|
|System ADMIN|Manage teams,pools,quotas; read everylease; revoke leases in P1.|
|Team OWNER / ADMIN|Manage membershipof own team; view own team data.|
|Team MEMBER|See authorized teampools andquota; acquire units; manage own leases.|
|External application|Authenticated technical principal integrated using the same owner/team authorization model;<br>dedicated service accounts are future scope.|



Bootstrap: seed the first system ADMIN through controlled deployment setup. Never accept systemRole or accountStatus from selfregistration. 

### **3. Endpoint catalog** 

The following endpoints are grouped by responsibility. P0 is the initial implementation; P1 is introduced with the queue and audit migration. 

|**METHOD**|**ROUTE (under /api/v1)**|**ACCESS**|**PHASE**|
|---|---|---|---|
|POST|/auth/register|Public*|P0|
|POST|/auth/login|Public*|P0|
|GET|/auth/me|Auth|P0|
|GET|/teams|Team / Admin|P0|
|POST|/teams|Admin|P0|
|GET|/teams/{teamId}|Team / Admin|P0|
|GET|/teams/{teamId}/members|Team / Admin|P0|
|POST|/teams/{teamId}/members|Owner / Admin|P0|
|GET|/pools|Auth|P0|
|POST|/pools|Admin|P0|
|GET|/pools/{poolId}|Auth|P0|
|PATCH|/pools/{poolId}|Admin|P0|
|GET|/teams/{teamId}/quotas/{poolId}|Team / Admin|P0|
|PUT|/teams/{teamId}/quotas/{poolId}|Admin|P0|
|POST|/allocations|Team member|P0|
|GET|/leases|Owner / Admin|P0|
|GET|/leases/{leaseId}|Owner / Admin|P0|
|POST|/leases/{leaseId}/renew|Owner / Admin|P0|
|POST|/leases/{leaseId}/release|Owner / Admin|P0|
|GET|/requests/{requestId}|Owner / Admin|P1|
|POST|/requests/{requestId}/cancel|Owner / Admin|P1|
|POST|/leases/{leaseId}/revoke|Admin|P1|
|GET|/analytics/overview|Admin|P1|
|GET|/audit-events|Admin|P1|



LeaseGrid • API v1.0   |   2 

**LEASEGRID  /  API DESIGN SYSTEM** 

* Only POST /auth/register and POST /auth/login are public; GET /auth/me requires a valid access token. Full access behavior is specified under each operation in OpenAPI. 

### **4. JSON DTO conventions** 

The DTO layer must be separate from JPA entities. Do not expose password_hash, internal row-lock state, or raw database objects. Use request DTO validation (@Valid) and response projections. Never allow clients to supply allocatedCapacity, usedUnits, ownerId or lease status during creation. 

|**DTO**|**IMPORTANT FIELDS**|
|---|---|
|PoolCreateRequest|code, name, resourceType, totalCapacity, maxLeaseSeconds|
|ResourcePool|id, code, resourceType, totalCapacity, allocatedCapacity, availableCapacity, status|
|QuotaUpsertRequest|quotaLimit|
|TeamQuota|teamId,poolId,quotaLimit, usedUnits, remainingUnits|
|AllocationCreateRequest|poolId, teamId,quantity, durationSeconds,queueIfUnavailable[P1]|
|Lease|id,poolId, teamId, ownerId,quantity, status, issuedAt, expiresAt|
|RenewLeaseRequest|requestedExpiresAt(absolute date-time)|
|QueuedAllocation[P1]|requestId, status,poolId, teamId,quantity, createdAt|



### **5. Critical endpoint: POST /allocations** 

#### **5.1 Request** 

Requires JWT and Idempotency-Key. A new key represents a new intent; retry with the same key only when repeating the same operation. Requests must identify a pool and authorized team, positive quantity and duration within the pool policy. 

POST /api/v1/allocations Authorization: Bearer <jwt> Idempotency-Key: alloc-unique-001 Content-Type: application/json 

{ "poolId": "11111111-1111-4111-8111-111111111111", "teamId": "22222222-2222-4222-8222-222222222222", "quantity": 2, "durationSeconds": 1800, "queueIfUnavailable": false } 

#### **5.2 Success — 201 Created** 

Location: /api/v1/leases/33333333-3333-4333-8333-333333333333 

{ "id": "33333333-3333-4333-8333-333333333333", "poolId": "11111111-1111-4111-8111-111111111111", "teamId": "22222222-2222-4222-8222-222222222222", "ownerId": "55555555-5555-4555-8555-555555555555", "quantity": 2, "status": "ACTIVE", "issuedAt": "2026-11-19T10:00:00Z", "expiresAt": "2026-11-19T10:30:00Z", "renewCount": 0 } 

#### **5.3 Other outcomes** 

|**STATUS**|**WHEN**|**EXPECTED RESULT**|
|---|---|---|
|409|Not enough capacity<br>f|CAPACITY_EXCEEDED; counters unchanged.|
|409|Teamquota insufficient<br>f|QUOTA_EXCEEDED; counters unchanged.|
|409|Idempotency key used for different<br>payload|IDEMPOTENCY_KEY_REUSED; no allocation.|
|422|queueIfUnavailable=true in P0|QUEUE_NOT_ENABLED; no requestqueued.|



LeaseGrid • API v1.0   |   3 

**LEASEGRID  /  API DESIGN SYSTEM** 

|**STATUS**|**WHEN**|**EXPECTED RESULT**|
|---|---|---|
|202 [P1]|Queue is enabled and immediate grant is<br>unavailable|QueuedAllocation + Location: /requests/{id}.|
|403|Caller lacks team membership|FORBIDDEN; no resource information disclosed.|



### **6. Transactional allocation contract** 

Inside one PostgreSQL transaction: claim the (callerId, idempotencyKey) unique row; lock the pool; lock the (teamId, poolId) quota; re-check availability and policy; update pool and quota counters; insert ACTIVE lease; persist the stable response; commit. All capacity-changing paths use an agreed global lock order. 



<!-- Start of picture text -->
Client API / Service PostgreSQL Result<br>EE<br>BEGIN: lock pool then quota<br>——————<br>cked rows + current counts<br>—avalidate; insert lease; update counts<br>—————————————201 + lease1D<br><!-- End of picture text -->

Simplified allocation sequence: request → transaction → lease → 201. Idempotency claiming and authorization are omitted from this illustration. 

Core invariants: 0 ≤ pool.allocatedCapacity ≤ pool.totalCapacity; 0 ≤ teamQuota.usedUnits ≤ teamQuota.quotaLimit; a successful lease must be backed by exactly the corresponding pool and quota counters. Database CHECK constraints enforce individual row bounds, while common transactions enforce cross-table conservation. 

Use READ COMMITTED with SELECT ... FOR UPDATE or equivalent guarded updates. On conflicting requests, one transaction is allowed to commit first; the other sees the updated state and can return 409. No Java synchronized block or Redis lock is an authority for grant decisions. 

### **7. Lease operations and state semantics** 

|**ROUTE**|**RULE / BEHAVIOR**|
|---|---|
|GET /leases/{leaseId}|Returns persisted lease state; expiredAt may already be in past while sweeper has not yet<br>reclaimed counters.|
|POST /leases/{id}/renew|Body is requestedExpiresAt (absolute UTC time). Only ACTIVE and unexpired. Server caps<br>expiryat issuedAt +pool.maxLeaseSeconds. Repeatingsame target is a no-op.|
|POST /leases/{id}/release|First valid ACTIVE→RELEASED transition returns counters once. Repeating release returns<br>RELEASED. If alreadyexpired, reclaim EXPIRED.<br>i|
|POST /leases/{id}/revoke[P1]|Admin + reason; first ACTIVE→REVOKED transition restores counters and logs audit event.|



#### **7.1 Renewal example** 

POST /api/v1/leases/{leaseId}/renew Content-Type: application/json 

{"requestedExpiresAt":"2026-11-19T10:45:00Z"} 

200 OK → updated Lease (or unchanged if same deadline) 

Renewals never use “add another 15 minutes” because a lost response and retry could extend twice. An absolute target is idempotent by intent. If the deadline is passed when the server holds the lease row lock, renewal fails with LEASE_NOT_ACTIVE rather than reviving the lease. 

LeaseGrid • API v1.0   |   4 

**LEASEGRID  /  API DESIGN SYSTEM** 

#### **7.2 State and lock rules** 

|**EVENT**|**ALLOWED TRANSITION**|**LOCK ORDER**|
|---|---|---|
|Acquire|(none)→ ACTIVE|Idempotency→pool →quota|
|Release|ACTIVE → RELEASED|pool →quota → lease|
|Expire|ACTIVE → EXPIRED|pool →quota → lease|
|Revoke[P1]|ACTIVE → REVOKED|pool →quota → lease|
|Renew|ACTIVE → ACTIVE(new expiry)|pool → lease|



An ACTIVE row past expiresAt is not a valid lease, but its capacity remains conservatively reserved until the expiry/release transaction marks it terminal. This prevents over-allocation during sweeper delays. 

### **8. Idempotency, replay and retries** 

Only POST /allocations requires an Idempotency-Key in P0. Keys are unique per authenticated caller, and their request hash includes the normalized allocation payload. Retain records for at least 8 days under this design, covering the maximum 7- day lease plus retry buffer. A key must not be reused for a distinct allocation intent. 

|**SCENARIO**|**CONTRACT**|
|---|---|
|Same key, same payload, completed|Replay the original HTTP status/body; include Idempotency-Replayed: true where<br>applicable.|
|Same key, changedpayload|409 IDEMPOTENCY_KEY_REUSED; never allocate.|
|Simultaneous duplicate requests|Unique-keyclaim serializes; the loser waits for committed response or safelyretries.<br>l|
|Committed error(e.g.quota rejection)|Replaythe stored deterministic conflict; use a new keyfor a new attempt.|
|Crash before transaction commit|No committed allocation or completed record; safe retryafter database recovery.|
|500/503 transient failure|Do not cache uncommitted server failure as a completed allocation.|



Release is naturally repeat-safe through state transitions. Renew is repeat-safe by setting a target expiration time. Neither needs the V1 idempotency_records table. 

### **9. Queue and scheduling API — P1** 

When queueIfUnavailable=true, a valid request may receive 202 Accepted instead of a lease. The response contains requestId and a Location header pointing to GET /requests/{requestId}. On grant, the status becomes GRANTED and the response links to a lease via leaseId. 

HTTP/1.1 202 Accepted Location: /api/v1/requests/44444444-4444-4444-8444-444444444444 

{ "requestId":"44444444-4444-4444-8444-444444444444", 

"status":"QUEUED", "poolId":"11111111-1111-4111-8111-111111111111", 

"teamId":"22222222-2222-4222-8222-222222222222", "quantity":2, "createdAt":"2026-11-19T10:00:00Z" 

} 

|**API**|**BEHAVIOR**|
|---|---|
|GET /requests/{requestId}|Returns QUEUED, GRANTED, REJECTED, CANCELLED, or TIMED_OUT; includes leaseId if<br>granted.|
|POST /requests/{requestId}/cancel|Only queued requests can become CANCELLED; replaying cancellation returns<br>CANCELLED; alreadyGRANTED returns 409.|
|Strict FIFO rule|For a given pool, existing queued requests have precedence over fresh allocations; new<br>requests maynotjumpthequeue.<br>i|
|Scheduling implementation|Lock pool first; identify current queue head; lock head and quota safely; grant atomically,<br>or leave itqueued.|
|Queue policy|Quota is checked again at grant time; expired/cancelled requests are not granted. Head-<br>of-line blockingis accepted in initial FIFO.|



Schema alignment: queued requests require V2 allocation_requests, and queued lease attribution requires V2 leases.request_id. The API response is not permission to use GPU capacity until GRANTED. 

LeaseGrid • API v1.0   |   5 

**LEASEGRID  /  API DESIGN SYSTEM** 

### **10. HTTP errors and validation** 

All error bodies use application/problem+json with RFC-style fields plus code and traceId extensions. Clients should branch on code rather than searching human-readable detail strings. 

HTTP/1.1 409 Conflict Content-Type: application/problem+json { "type":"about:blank", "title":"Conflict", "status":409, "detail":"Only one GPU slot remains", "instance":"/api/v1/allocations", "code":"CAPACITY_EXCEEDED", "traceId":"tr-01JX" } 

|**HTTP**|**EXAMPLE CODE**|**MEANING**|
|---|---|---|
|400|VALIDATION_ERROR|Malformed bodyor invalid field format.|
|401|UNAUTHENTICATED|Missing, invalid or expired JWT.|
|403|FORBIDDEN|Authenticated but lacks team/rolepermission.|
|404|NOT_FOUND|Requested resource not visible or does not exist.|
|409|CAPACITY_EXCEEDED|Insufficient capacity; no allocation.|
|409|QUOTA_EXCEEDED|Insufficient teamquota.|
|409|LEASE_NOT_ACTIVE|Invalid lease transition.|
|409|IDEMPOTENCY_KEY_REUSED|Keyalreadyassociated with differentpayload.|
|422|QUEUE_NOT_ENABLED|P0queuingunavailable.|
|422|LEASE_DURATION_LIMIT|Duration or target expiryexceedspolicy.|
|429|RATE_LIMITED|Too manyAPI requests; Retry-After if known.|
|503|SERVICE_UNAVAILABLE|Service or PostgreSQL temporarilyunavailable.|



Validation examples: quantity > 0, duration 60–604800 seconds AND at most pool.maxLeaseSeconds, maxLeaseSeconds 60–604800, nonempty names, UUID path IDs, authenticated team membership, and payload constraints. The API must reject unknown writable fields or ignore them consistently; the contract uses schemas with additionalProperties=false. 

### **11. Pagination and list behavior** 

List endpoints use ?limit=20&cursor=... and return {"items":[...],"nextCursor":"..."}. When no further page exists, omit nextCursor. Cursors must be opaque, tied to the ordering and active filters, and validated so clients cannot fetch a different user’s records. 

Lease lists order by (issuedAt DESC, id DESC). Pool and team lists should use deterministic (createdAt DESC, id DESC) or an equally stable sort. Audit events use (occurredAt DESC, id DESC). Any user-supplied teamId filters are intersected with server-side authorization. 

### **12. Security and observability** 

- JWT bearer access tokens should be short lived; P0 uses 15 minutes. No refresh endpoint or immediate logout/revocation guarantee in this contract. Suspend account checks require server-side user lookup or equivalent security controls. 

- Apply rate limiting to authentication and allocation endpoints; 429 is distinct from resource-capacity 409. 

- Do not log Authorization headers, password values, personal secrets, or raw idempotency response bodies indiscriminately. 

- Add correlation/traceId to logs and problem responses. Track allocations granted/rejected, quota denials, p95 latency, queue wait, expired-lease recovery, and transaction contention. 

- Spring Actuator health and Prometheus scrape endpoints are operational (not /api/v1 business endpoints). Keep metrics and management endpoints internal or protected. 

- Use server/database time for lease deadlines. Show UTC ISO-8601 timestamps to callers, regardless of developer timezone. 

LeaseGrid • API v1.0   |   6 

**LEASEGRID  /  API DESIGN SYSTEM** 

### **13. Acceptance tests — API contract** 

|**TEST CASE**|**EXPECTED RESULT**|
|---|---|
|100 requests for 10 units|At most 10 units committed; zero cross-table conservation violations.|
|Concurrent same-keyrequests|Exactlyone lease, all successful retries receive original result.|
|Same keyand changed body|409 IDEMPOTENCY_KEY_REUSED.|
|Teamquota alreadyfull|409 QUOTA_EXCEEDED, no counter change.|
|Pool DRAINING|New request returns 409; existingleases remain accessible.<br>i|
|Release / release race|Onlyfirst terminal transition decreases counters.|
|Release vs expiryrace|Single terminal transition; capacityrestored once.|
|Renew vs expire race|A missed deadline never resurrects a lease.|
|Two SpringBoot instances|Same capacitycorrectness as one instance.|
|P1queue strict FIFO|New immediate requests do not bypass existing queue head.|
|No JWT / wrongteam|401 / 403; no unauthorized side effect.|
|Postgres failure before commit|Nopartial allocation; safe retry.|



### **14. Spring Boot controller and service mapping** 

|**REST CONTROLLER**|**SERVICE / RESPONSIBILITY**<br>i|
|---|---|
|AuthController|AuthService + SecurityConfig— identityand JWT.|
|TeamController|TeamService — membershipand access rules.|
|ResourcePoolController|ResourcePoolService —pool configuration and reads.|
|TeamQuotaController|QuotaService — safequota updates.|
|AllocationController|AllocationService — transactional acquisition + idempotency.|
|LeaseController|LeaseService — view, renew, release, revoke.|
|AllocationRequestController[P1]|QueueService — status, cancellation.|
|AnalyticsController[P1]|AnalyticsService — derived reporting.|
|Internal workers|LeaseExpirationWorker; QueueScheduler[P1].|



Use @RestController + @RequestMapping("/api/v1"), DTOs annotated with Jakarta Validation, @Transactional service boundaries, Spring Data/JDBC for targeted locking SQL, and @RestControllerAdvice for standardized problem responses. 

### **15. Build order and sign-off** 

1. Implement authentication and admin bootstrap; verify JWT authorization. 

2. Implement teams, memberships, pools, and quota APIs using PostgreSQL V1. 

3. Implement POST /allocations with idempotency + transactional pool/quota/lease writes. 

4. Implement GET/renew/release leases, then expiry worker and recovery tests. 

5. Freeze P0 OpenAPI, generate Swagger documentation, run correctness/load tests. 

6. Apply V2 migration; add queue, audit, revoke, analytics, and distributed workers. 

7. Add React dashboard, Docker, multiple instances, monitoring, and optimization benchmarks. 

Final contract rule: The API never promises physical GPU ownership beyond the logical lease. Real hardware enforcement requires a compute-agent integration and fencing-token-aware access control in a later release. 

LeaseGrid • API v1.0   |   7 

