# LeaseGrid
LeaseGrid leases logical units from shared resource pools. PostgreSQL owns the pool and team counters and lease deadlines.

## Run locally
Set POSTGRES_USER and POSTGRES_PASSWORD for both Compose and the application. Set JWT_SECRET to a Base64 encoding of at least 32 cryptographically random bytes. The .env.example file lists configuration keys. Compose automatically reads .env. Export the same variables into the shell when running Maven.

To run the whole stack, configure .env and run `docker compose up --build`. For development with Maven, start PostgreSQL with `docker compose up -d postgres`. Run `./mvnw spring-boot:run` (Windows: `.\mvnw.cmd spring-boot:run`). Flyway applies the core, queue and transactional audit migrations at startup. The API listens on port 8080.

Register using POST /api/v1/auth/register with email, displayName and password, then POST /api/v1/auth/login with email and password. Send the accessToken as a Bearer token. An operator with database access can promote a verified registered account using a parameterized update to users.system_role for its exact UUID. A system admin creates a team and a pool, then puts a quota for that team and pool. The team creator receives OWNER membership. Members can then POST /api/v1/allocations with an Idempotency-Key header and the teamId, poolId, quantity and durationSeconds.

## Transactions
Acquisition claims the caller-scoped key, locks the pool and locks its team quota in READ COMMITTED isolation. Counter increments, lease insertion and the stored response commit together. A repeated key with the same normalized payload receives the stored result. Release and expiration lock pool, quota and lease, then recheck the state. Renewal locks the pool and lease and compares the deadline to database time.

## Verification
Run `./mvnw test` with Docker available for PostgreSQL 16 Testcontainers. For a local PostgreSQL binary managed by the test harness use `./mvnw -Dleasegrid.test.embedded=true test`. The embedded version may differ from the deployment version. Run `docker build -t leasegrid .` to build the application image.

The target API is in [OpenAPI](docs/LeaseGrid_OpenAPI_v1.yaml). See the [implementation checklist](docs/IMPLEMENTATION.md) for scope and outstanding work.

