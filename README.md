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


## Architecture and operations

Spring MVC controllers handle authentication, management, allocations and leases. Transactional services use JdbcClient and PostgreSQL row locks. Scheduled workers expire leases and grant queued requests. Flyway owns schema changes. PostgreSQL stores the audit trail alongside the business records.

For a new installation, copy `.env.example` to `.env` and supply independent database and JWT secrets. Preserve the configured credentials when reusing a volume. On PowerShell use `Copy-Item .env.example .env` only during initial setup. Compose binds both services to loopback. Configure HTTPS at the ingress before exposing the API externally.

| Command | Purpose |
| --- | --- |
| `docker compose up -d --build --wait` | Build, apply pending migrations and wait for readiness |
| `docker compose ps` | Check container health |
| `docker compose logs --tail 100 app` | Inspect startup and application logs |
| `docker compose down` | Stop containers and retain the named PostgreSQL volume |

Open [Swagger UI](http://localhost:8080/swagger-ui.html). Execute login, copy the access token and paste it into **Authorize**. The UI loads the [packaged API contract](http://localhost:8080/docs/LeaseGrid_OpenAPI_v1.yaml). The registration and login operations are public. The other operations require a bearer token and apply the relevant membership, ownership or administrator checks. See the [Postman walkthrough](docs/postman/README.md) for a complete example.

Validation errors generally return 400. Invalid credentials or missing authentication return 401. Authorization failures return 403 or visibility-scoped 404. Allocation conflicts return 409 with codes such as QUOTA_EXCEEDED. A duration above the pool maximum returns 422. Controller problem responses include a code and traceId. Security filter responses can have an empty body.

Actuator exposes health only. Use `/actuator/health/liveness` for process liveness and `/actuator/health/readiness` for application readiness including database connectivity. Responses omit component details. Restrict access to interactive documentation at ingress as appropriate.

CI runs `sh mvnw -B verify` on Java 21 with a disposable PostgreSQL 16 Testcontainer, then builds the runtime image on success. On Windows use `.\mvnw.cmd -B verify`. The workflow runs for pushes and pull requests and performs no publish or deploy step.
