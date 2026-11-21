import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import encoding from 'k6/encoding';

const base = __ENV.BASE_URL || '';
const burstSize = Number(__ENV.BURST_VUS || 100);
const grants = new Counter('grant_responses');
const allocations = new Counter('successful_allocations');
const rejects = new Counter('business_rejections');
const errors = new Rate('unexpected_errors');
const allocationStatuses = http.expectedStatuses(201, 409);
const opts = (name) => ({
    headers: { Authorization: 'Bearer ' + __ENV.JWT_TOKEN, 'Content-Type': 'application/json' },
    tags: { name }, redirects: 0, timeout: '10s',
});

export function configure(mode) {
    const burst = ['concurrency', 'idempotency'].includes(mode);
    const targets = mode === 'stress' ? [25, 50, 100, 150, 200] : [10, 25, 50, 100];
    const seconds = Number(__ENV.STAGE_SECONDS || 20);
    if (!Number.isInteger(seconds) || seconds < 1 || seconds > 40 ||
        !Number.isInteger(burstSize) || burstSize < 1 || burstSize > 500) throw Error('Invalid test parameters');
    const stages = targets.flatMap(target => [
        { target, duration: seconds + 's' }, { target, duration: seconds + 's' },
    ]).concat({ target: 0, duration: seconds + 's' });
    const scenario = burst ? { executor: 'per-vu-iterations', vus: burstSize, iterations: 1, maxDuration: '60s' }
        : { executor: 'ramping-vus', startVUs: 0, stages };
    const thresholds = { unexpected_errors: ['rate==0'], http_req_duration: ['p(95)<500', 'p(99)<1500'],
        grant_responses: ['count>0'] };
    if (burst) thresholds.iterations = ['count==' + burstSize];
    if (mode === 'idempotency') {
        thresholds.grant_responses = ['count==' + burstSize];
        thresholds.successful_allocations = ['count==1'];
    }
    return { scenarios: { [mode]: scenario }, thresholds, summaryTrendStats: ['avg','p(95)','p(99)','max'],
        systemTags: ['status','method','name','scenario','expected_response'] };
}

export function prepare() {
    if (!/^http:\/\/127\.0\.0\.1:\d+\/api\/v1$/.test(base) || !__ENV.RUN_ID ||
        !__ENV.TEAM_ID || !__ENV.POOL_ID) throw Error('Use run.ps1 to provision the disposable fixture');
    const claims = JSON.parse(encoding.b64decode(__ENV.JWT_TOKEN.split('.')[1], 'rawurl', 's'));
    if (claims.exp * 1000 < Date.now() + 540000) throw Error('JWT lifetime too short');
    const pool = http.get(base + '/pools/' + __ENV.POOL_ID, opts('fixture')).json();
    if (pool.code !== __ENV.RUN_ID || pool.allocatedCapacity !== 0) throw Error('Unexpected fixture');
    return { start: Date.now() + 2000 };
}

export function exercise(mode, fixture) {
    const burst = mode === 'concurrency' || mode === 'idempotency';
    if (burst) sleep(Math.max(0, fixture.start - Date.now()) / 1000);
    const options = opts('allocate');
    options.headers['Idempotency-Key'] = __ENV.RUN_ID + '-' +
        (mode === 'idempotency' ? 'shared' : exec.scenario.iterationInTest);
    options.responseCallback = allocationStatuses;
    const res = http.post(base + '/allocations', JSON.stringify({
        teamId: __ENV.TEAM_ID, poolId: __ENV.POOL_ID, quantity: 1,
        durationSeconds: 1200, queueIfUnavailable: false,
    }), options);
    let body = {};
    try { body = res.json() || {}; } catch (_) { /* Non-JSON responses count as errors. */ }
    const denied = res.status === 409 && ['QUOTA_EXCEEDED','CAPACITY_EXCEEDED'].includes(body.code);
    if (denied) rejects.add(1, { code: body.code });
    const granted = res.status === 201 && body.id && body.status === 'ACTIVE' &&
        Date.parse(body.expiresAt) > Date.now() + 30000;
    grants.add(granted ? 1 : 0);
    allocations.add(granted && res.headers['Idempotency-Replayed'] !== 'true' ? 1 : 0);
    errors.add(!granted && !(denied && mode !== 'idempotency'));
    if (granted && !burst) {
        const release = http.post(base + '/leases/' + body.id + '/release', null, opts('release'));
        errors.add(release.status !== 200 || release.json('status') !== 'RELEASED');
    }
    if (!burst) sleep(0.1);
}
