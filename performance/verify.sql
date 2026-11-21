WITH usage AS (
    SELECT count(*) AS created,
           coalesce(sum(quantity) FILTER (WHERE status='ACTIVE' AND expires_at > now()),0) AS active,
           count(*) FILTER (WHERE status='EXPIRED' OR (status='ACTIVE' AND expires_at <= now())) AS expired,
           count(*) FILTER (WHERE status='RELEASED') AS released
    FROM leases WHERE pool_id=:'pool' AND team_id=:'team'
), keys AS (
    SELECT count(*) AS attempts, count(*) FILTER (WHERE http_status=201) AS grants,
           count(*) FILTER (WHERE record_status <> 'COMPLETED'
               OR (http_status=201 AND (lease_id IS NULL OR response_body->>'id' IS DISTINCT FROM lease_id::text))) AS pending
    FROM idempotency_records WHERE caller_id=:'caller'
)
SELECT created, active, expired, released, attempts, grants, allocated_capacity, used_units,
       (created > 0 AND created=grants AND pending=0 AND expired=0
        AND active=allocated_capacity AND active=used_units
        AND active<=total_capacity AND active<=quota_limit
        AND CASE :'mode'
            WHEN 'idempotency' THEN created=1 AND active=1 AND attempts=1
            WHEN 'concurrency' THEN attempts=:vus AND created=least(:vus,total_capacity,quota_limit) AND active=created
            ELSE released=created AND active=0 END) AS passed
FROM usage CROSS JOIN keys CROSS JOIN resource_pools p
JOIN team_quotas q ON q.pool_id=p.id WHERE p.id=:'pool' AND q.team_id=:'team';
