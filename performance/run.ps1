param([Parameter(Mandatory)][ValidateSet('load','stress','concurrency','idempotency')][string]$Mode)
$ErrorActionPreference = 'Stop'
$null = Get-Command k6,docker
$project = 'leasegrid-perf-' + [guid]::NewGuid().ToString('N')
$compose = @('-p',$project,'-f',"$PSScriptRoot/../compose.yaml",'-f',"$PSScriptRoot/compose.override.yaml")
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
    $email = "$project@example.test"
    $password = if ($env:PERF_PASSWORD) { $env:PERF_PASSWORD } else { Secret }
    $user = Api auth/register @{email=$email;password=$password;displayName='Benchmark member'}
    $caller = ([guid]::Parse($user.id)).ToString()
    $env:JWT_TOKEN = (Api auth/login @{email=$email;password=$password}).accessToken
    $sql = @"
BEGIN;
INSERT INTO teams(id,name,created_by) VALUES ('$env:TEAM_ID','$project','$caller');
INSERT INTO team_memberships VALUES ('$env:TEAM_ID','$caller','MEMBER',now());
INSERT INTO resource_pools(id,code,name,resource_type,total_capacity,max_lease_seconds)
VALUES ('$env:POOL_ID','$project','Benchmark pool','GENERIC',$capacity,1200);
INSERT INTO team_quotas(team_id,pool_id,quota_limit) VALUES ('$env:TEAM_ID','$env:POOL_ID',$quota);
COMMIT;
"@
    $sql | docker compose @compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U benchmark -d leasegrid
    if ($LASTEXITCODE) { throw 'Fixture creation failed' }
    $outputArgs = @()
    if ($env:PERF_OUTPUT) { $outputArgs = @('--out',"json=$env:PERF_OUTPUT") }
    & k6 run @outputArgs "$PSScriptRoot/$Mode-test.js"
    $testExit = $LASTEXITCODE
    $result = Get-Content "$PSScriptRoot/verify.sql" -Raw | docker compose @compose exec -T postgres psql -X -At -v ON_ERROR_STOP=1 -v "caller=$caller" -v "team=$env:TEAM_ID" -v "pool=$env:POOL_ID" -v "mode=$Mode" -v "vus=$env:BURST_VUS" -U benchmark -d leasegrid
    Write-Host "created|active units|expired|released|keys|grant keys|pool used|quota used|passed"
    Write-Host $result
    if ($LASTEXITCODE -or "$result" -notmatch '\|t$') { throw 'Database invariants failed' }
    if ($testExit) { throw "k6 failed ($testExit)" }
} finally {
    try { if ($started) { Compose down --volumes } }
    finally { foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key]) } }
}
