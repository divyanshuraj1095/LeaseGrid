# Implementation decisions
The API Design governs public behavior. Allocation retries replay the original HTTP status and body, including committed deterministic conflicts. Caller-scoped keys are retained for at least eight days. Over-limit renewal returns HTTP 422 with LEASE_DURATION_LIMIT. Schema migrations and OpenAPI are reconstructed from the four Markdown documents.

## Milestones
- [x] PostgreSQL configuration and Flyway V1/V2 schema files
- [x] JWT registration/login, identity lookup, team membership and pool/quota administration
- [x] Transactional allocation, quota accounting and replay
- [x] Lease lifecycle and expiry recovery
- [x] Strict FIFO scheduling, cancellation, audit and analytics
- [x] Isolated PostgreSQL integration tests, executable JAR, Compose and setup guide
- [ ] Docker-based PostgreSQL 16 run, two-instance verification and restart exercise
- [ ] Rate limiting, operational metrics, reconciliation monitoring and measured load benchmark

