# LeaseGrid performance tests

## Run locally on Windows

Install Docker Desktop with a Linux engine and Compose 2.24.4 or newer. Put docker.exe and k6.exe on PATH. Install k6 with `winget install k6 --source winget`, reopen PowerShell and check `k6 version` and `docker compose version`. [Grafana installation guide](https://grafana.com/docs/k6/latest/set-up/install-k6/)

From the repository root, start with a small smoke run:

```powershell
$env:BURST_VUS = '2'
.\performance\run.ps1 idempotency
Remove-Item Env:BURST_VUS
```

Run each full workload separately on an idle test machine:

```powershell
.\performance\run.ps1 load
.\performance\run.ps1 concurrency
.\performance\run.ps1 idempotency
.\performance\run.ps1 stress
```

Each invocation builds a fresh, uniquely named Compose project, waits for Flyway and readiness, registers a member, logs in and seeds one team, membership, pool and quota transactionally. PostgreSQL has no published port. The script verifies the database even after k6 threshold failures, then removes only the generated project's containers and volumes. Use the wrapper for every run. If interrupted by termination of PowerShell itself, identify the exact generated project with `docker compose ls -a` before cleaning it up. Never target a production Docker context or forward its traffic to this runner.

## Workloads

| Script | Traffic and correctness criterion |
| --- | --- |
| load-test.js | Ramp and hold at 10, 25, 50 and 100 VUs, then ramp down. Each iteration requests one unit, releases a grant and pauses 100 ms. |
| concurrency-test.js | 100 one-shot VUs share a start deadline and send distinct keys. Default pool 12, quota 8. Exactly eight leases must exist. To exercise pool exhaustion instead, set CAPACITY=8 and QUOTA=12. Client scheduling and network latency determine actual arrival spread. |
| idempotency-test.js | Same caller, key and payload across a synchronized burst. Every request must return a grant, exactly one response must be original, and PostgreSQL must contain one completed key and one active lease. |
| stress-test.js | Ramp and hold at 25, 50, 100, 150 and 200 VUs, then ramp down. Acquire/release traffic exercises lock contention on one pool and quota. |

The service scopes idempotency keys by caller and locks the pool and quota before granting. Database constraints bound allocated capacity and used quota. All runs request 1,200-second leases, use a newly issued 900-second JWT and reject insufficient remaining token lifetime. Stage lengths are bounded so both outlast the scheduled run. Responses must contain a live lease before release. SQL compares counters to non-overdue ACTIVE quantities, counts released leases separately and fails a run containing expired or overdue leases. Burst tests also count historical grants, so expiration cannot conceal over-allocation.

## Configuration

Set process environment variables in PowerShell before running. The wrapper restores the variables it sets.

| Variable | Meaning |
| --- | --- |
| PERF_PORT / BASE_URL | Default 18080 and http://127.0.0.1:18080/api/v1. An override URL must match the selected local port; startup fails if it is occupied. |
| PERF_PASSWORD | Optional registration password (8–72 UTF-8 bytes). Default generated per run. JWT_TOKEN is obtained by login and supplied to k6. |
| TEAM_ID / POOL_ID | Optional UUIDs for newly inserted fixtures inside the isolated database. Otherwise generated. |
| CAPACITY / QUOTA | Positive integers. Defaults 256/256 except concurrency (12/8). |
| BURST_VUS | 1–500, default 100, for the two one-shot tests. |
| STAGE_SECONDS | 1–40, default 20, for each ramp and hold. |
| PERF_OUTPUT | Optional k6 JSON time-series output file, e.g. `$env:PERF_OUTPUT = "$env:TEMP\leasegrid-stress.json"`. Choose a new path per run. |

## Reading the results

Read http_reqs per second for total HTTP throughput (includes releases and setup), successful_allocations per second for newly granted leases, and grant_responses for all successful responses including replays. http_req_duration includes average, p95 and p99 in milliseconds. Filter exported samples by name=allocate or name=release to separate the operations.

http_req_failed uses status-based expectations. Allocation 409s are classified further by unexpected_errors: only QUOTA_EXCEEDED and CAPACITY_EXCEEDED are accepted business refusals. business_rejections counts those by code. Other conflicts, authentication failures, invalid grant responses, transport errors and 5xx responses fail correctness. The final SQL result must also pass. Treat a nonzero runner exit as a failed run.

Initial hypotheses are aggregate p95 below 500 ms and p99 below 1,500 ms, with zero unexpected errors. These are starting thresholds for this workload. For stress analysis use PERF_OUTPUT, align the timestamped latency and error samples to each ramp/hold interval, and compare each hold's allocation throughput, percentiles and error rate against the preceding hold. Look for the first repeatable rise in latency or errors as throughput flattens. A closed-loop VU workload slows its request rate when the server slows. Repeat with the same hardware, Docker limits and stage lengths, and inspect generator CPU before attributing saturation to LeaseGrid.
