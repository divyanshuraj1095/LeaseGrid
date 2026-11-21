param([Parameter(Mandatory)][ValidateSet('load','stress','concurrency','idempotency')][string]$Mode)
$ErrorActionPreference = 'Stop'
$null = Get-Command k6,docker
$project = 'leasegrid-perf-' + [guid]::NewGuid().ToString('N')
$compose = @('-p',$project,'-f',"$PSScriptRoot/../compose.yaml",'-f',"$PSScriptRoot/compose.override.yaml")
$compose += @("-f","$PSScriptRoot/results/lock-baseline/metrics.yaml")
function Compose {
    & docker compose @compose @args
    if ($LASTEXITCODE) { throw "Compose failed ($LASTEXITCODE)" }
}
function Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($bytes)
}
function Positive($value,$fallback) {
    if (!$value) { return $fallback }
    $n = [int]$value
    if ($n -lt 1) { throw 'Expected positive integer' }
    return $n
}
function FixtureId($value) {
    if ($value) { return ([guid]::Parse($value)).ToString() }
    return [guid]::NewGuid().ToString()
}
function Api($path,$body) {
    Invoke-RestMethod -Method Post -Uri "$env:BASE_URL/$path" -ContentType application/json -Body ($body | ConvertTo-Json) -MaximumRedirection 0
}
$previous = @{}
foreach ($key in 'POSTGRES_USER','POSTGRES_PASSWORD','JWT_SECRET','TEAM_ID','POOL_ID','RUN_ID','JWT_TOKEN','BASE_URL','PERF_PORT','BURST_VUS') {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key)
}
$started = $false
try {
    $env:PERF_PORT = Positive $env:PERF_PORT 18080
    $url = "http://127.0.0.1:$env:PERF_PORT/api/v1"
    if ($env:BASE_URL -and $env:BASE_URL -ne $url) { throw 'BASE_URL must match the dedicated loopback binding' }
    $env:BASE_URL = $url
    $env:POSTGRES_USER = 'benchmark'
    $env:POSTGRES_PASSWORD = Secret
    $env:JWT_SECRET = Secret
    $env:RUN_ID = $project
    $env:TEAM_ID = FixtureId $env:TEAM_ID
    $env:POOL_ID = FixtureId $env:POOL_ID
    $env:BURST_VUS = Positive $env:BURST_VUS 100
    $capacity = Positive $env:CAPACITY $(if ($Mode -eq 'concurrency') {12} else {256})
    $quota = Positive $env:QUOTA $(if ($Mode -eq 'concurrency') {8} else {256})
    $started = $true
    Compose up --build -d --wait --wait-timeout 120
    foreach ($phase in "warmup","measured") {
    $fixture = "$project-$phase"
    $env:RUN_ID = $fixture
    $env:TEAM_ID = [guid]::NewGuid().ToString()
    $env:POOL_ID = [guid]::NewGuid().ToString()
    $email = "$fixture@example.test"
    $password = if ($env:PERF_PASSWORD) { $env:PERF_PASSWORD } else { Secret }
    $user = Api auth/register @{email=$email;password=$password;displayName='Benchmark member'}
    $caller = ([guid]::Parse($user.id)).ToString()
    $env:JWT_TOKEN = (Api auth/login @{email=$email;password=$password}).accessToken
    $sql = @"
BEGIN;
INSERT INTO teams(id,name,created_by) VALUES ('$env:TEAM_ID','$fixture','$caller');
INSERT INTO team_memberships VALUES ('$env:TEAM_ID','$caller','MEMBER',now());
INSERT INTO resource_pools(id,code,name,resource_type,total_capacity,max_lease_seconds)
VALUES ('$env:POOL_ID','$fixture','Benchmark pool','GENERIC',$capacity,1200);
INSERT INTO team_quotas(team_id,pool_id,quota_limit) VALUES ('$env:TEAM_ID','$env:POOL_ID',$quota);
COMMIT;
"@
    $sql | docker compose @compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U benchmark -d leasegrid
    if ($LASTEXITCODE) { throw 'Fixture creation failed' }
    if ($phase -eq "warmup") {
        & k6 run "$PSScriptRoot/concurrency-test.js" > "$(Split-Path $env:PERF_OUTPUT)/warmup.log"
        if ($LASTEXITCODE -notin 0,99) { throw "Warmup failed" }
    }
    }
    $outputArgs = @()
    if ($env:PERF_OUTPUT) { $outputArgs = @('--out',"json=$env:PERF_OUTPUT") }
    $dest=Split-Path $env:PERF_OUTPUT
    $appId=docker compose @compose ps -q app
    $pgId=docker compose @compose ps -q postgres
    $probeSql="select extract(epoch from clock_timestamp()),count(*),count(*) filter(where state='active'),count(*) filter(where wait_event_type='Lock'),count(*) filter(where wait_event_type='Lock' and query ilike '%from resource_pools%for update%') from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend';"
    ($probeSql + [char]10 + '\watch 0.05') | docker exec -i $pgId sh -c 'cat > /tmp/perf.sql'
    docker exec -d $pgId sh -c 'psql -XAt -U benchmark -d leasegrid -f /tmp/perf.sql >/tmp/activity'
    foreach($cid in $pgId,$appId){
        docker exec -d $cid sh -c 'while true; do echo $(date +%s.%N) $(head -1 /sys/fs/cgroup/cpu.stat) $(cat /sys/fs/cgroup/memory.current); sleep 0.2; done >/tmp/resources'
    }
    function Metrics($phase){
        $rows=foreach($metric in 'hikaricp.connections.acquire','hikaricp.connections.max','hikaricp.connections.timeout'){
            Invoke-RestMethod ("http://127.0.0.1:$env:PERF_PORT/actuator/metrics/"+$metric) -Headers @{Authorization="Bearer $env:JWT_TOKEN"}
        }
        $rows | ConvertTo-Json -Depth 8 | Set-Content "$dest/hikari-$phase.json"
    }
    Metrics before
    & k6 run @outputArgs "$PSScriptRoot/$Mode-test.js"
    $testExit = $LASTEXITCODE
    Metrics after
    docker cp "${pgId}:/tmp/activity" "$dest/db.txt"
    docker cp "${pgId}:/tmp/resources" "$dest/postgres.txt"
    docker cp "${appId}:/tmp/resources" "$dest/app.txt"
    $result = Get-Content "$PSScriptRoot/verify.sql" -Raw | docker compose @compose exec -T postgres psql -X -At -v ON_ERROR_STOP=1 -v "caller=$caller" -v "team=$env:TEAM_ID" -v "pool=$env:POOL_ID" -v "mode=$Mode" -v "vus=$env:BURST_VUS" -U benchmark -d leasegrid
    Write-Host "created|active units|expired|released|keys|grant keys|pool used|quota used|passed"
    Write-Host $result
    if ($LASTEXITCODE -or "$result" -notmatch '\|t$') { throw 'Database invariants failed' }
    if ($testExit) { throw "k6 failed ($testExit)" }
} finally {
    try { if ($started) { Compose down --volumes } }
    finally { foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key]) } }
}


