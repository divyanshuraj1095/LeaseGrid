package com.leasegrid.leasegrid.allocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
public class AllocationService {
    public record Result(int status, String body, boolean replayed) {}
    private record Pool(int free, int maximum, String status) {}
    private final JdbcClient db;
    private final ObjectMapper json;

    public AllocationService(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result allocate(@NotNull UUID caller, @NotBlank @Size(max=255) String key,
            @NotNull @Valid AllocationCommand cmd) {
        boolean authorized = db.sql("""
                select true from team_memberships m join users u on u.id=m.user_id
                where m.team_id=? and u.id=? and u.account_status='ACTIVE' for share of m,u
                """).params(cmd.teamId(), caller).query(Boolean.class).optional().orElse(false);
        if (!authorized) return problem(403,"FORBIDDEN");
        String hash = fingerprint(cmd);
        int claimed = db.sql("""
                insert into idempotency_records(caller_id,idem_key,request_hash)
                values(?,?,?) on conflict(caller_id,idem_key) do nothing
                """).params(caller,key,hash).update();
        if (claimed == 0) {
            return db.sql("""
                    select request_hash,http_status,response_body::text from idempotency_records
                    where caller_id=? and idem_key=?
                    """).params(caller,key).query((rs,n) -> hash.equals(rs.getString(1))
                    ? new Result(rs.getInt(2),rs.getString(3),true)
                    : problem(409,"IDEMPOTENCY_KEY_REUSED")).single();
        }
        Pool pool = db.sql("""
                select total_capacity-allocated_capacity,max_lease_seconds,status
                from resource_pools where id=? for update
                """).param(cmd.poolId()).query((rs,n) ->
                new Pool(rs.getInt(1),rs.getInt(2),rs.getString(3))).optional().orElse(null);
        Result result = decide(caller,cmd,pool);
        db.sql("""
                update idempotency_records set record_status='COMPLETED',http_status=?,
                response_body=cast(? as jsonb),lease_id=case when ?=201 then (cast(? as jsonb)->>'id')::uuid end
                where caller_id=? and idem_key=?
                """).params(result.status(),result.body(),result.status(),result.body(),caller,key).update();
        return result;
    }

    private Result decide(UUID caller, AllocationCommand cmd, Pool pool) {
        if (pool == null) return problem(404,"NOT_FOUND");
        if (!pool.status().equals("ACTIVE")) return problem(409,"POOL_NOT_ACTIVE");
        if (cmd.durationSeconds()>pool.maximum()) return problem(422,"LEASE_DURATION_LIMIT");

        int remaining = db.sql("""
                select quota_limit-used_units from team_quotas where team_id=? and pool_id=? for update
                """).params(cmd.teamId(),cmd.poolId()).query(Integer.class).optional().orElse(0);
        boolean pending=db.sql("select exists(select 1 from allocation_requests where pool_id=? and state='QUEUED')")
                .param(cmd.poolId()).query(Boolean.class).single();
        if (cmd.queueIfUnavailable() && (pending || remaining<cmd.quantity() || pool.free()<cmd.quantity())) {
            boolean hasQuota=db.sql("select exists(select 1 from team_quotas where team_id=? and pool_id=?)")
                    .params(cmd.teamId(),cmd.poolId()).query(Boolean.class).single();
            if(!hasQuota) return problem(409,"QUOTA_EXCEEDED");
            var body=db.sql("""
                    with q as (insert into allocation_requests(caller_id,pool_id,team_id,quantity,duration_seconds,state)
                    values(?,?,?,?,?,'QUEUED') returning id as "requestId",state as status,pool_id as "poolId",
                    team_id as "teamId",quantity,created_at as "createdAt") select to_jsonb(q)::text from q
                    """).params(caller,cmd.poolId(),cmd.teamId(),cmd.quantity(),cmd.durationSeconds()).query(String.class).single();
            return new Result(202,body,false);
        }
        if (remaining<cmd.quantity()) return problem(409,"QUOTA_EXCEEDED");
        if (pending) return problem(409,"CAPACITY_EXCEEDED");
        if (pool.free()<cmd.quantity()) return problem(409,"CAPACITY_EXCEEDED");
        return new Result(201,grant(caller,cmd,null),false);
    }

    String grant(UUID caller,AllocationCommand cmd,UUID requestId) {
        db.sql("update resource_pools set allocated_capacity=allocated_capacity+?,updated_at=now() where id=?")
                .params(cmd.quantity(),cmd.poolId()).update();
        db.sql("update team_quotas set used_units=used_units+?,updated_at=now() where team_id=? and pool_id=?")
                .params(cmd.quantity(),cmd.teamId(),cmd.poolId()).update();
        String lease = db.sql("""
                with t as (select clock_timestamp() as issued), granted as (
                insert into leases(pool_id,team_id,owner_id,quantity,status,issued_at,expires_at,request_id)
                select ?,?,?,?,'ACTIVE',issued,issued+make_interval(secs=>?),cast(? as uuid) from t
                returning id,pool_id as "poolId",team_id as "teamId",owner_id as "ownerId",
                quantity,status,issued_at as "issuedAt",expires_at as "expiresAt",renew_count as "renewCount")
                select to_jsonb(granted)::text from granted
                """).params(cmd.poolId(),cmd.teamId(),caller,cmd.quantity(),cmd.durationSeconds(),requestId).query(String.class).single();
        return lease;
    }

    private Result problem(int status,String code) {
        String title = org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase();
        return new Result(status,json.writeValueAsString(Map.of("status",status,"code",code,
                "type","about:blank","title",title,"detail",code,"instance","/api/v1/allocations",
                "traceId",UUID.randomUUID().toString())),false);
    }

    private static String fingerprint(AllocationCommand cmd) {
        String canonical = String.join("/",cmd.poolId().toString(),cmd.teamId().toString(),
                Integer.toString(cmd.quantity()),Integer.toString(cmd.durationSeconds()),Boolean.toString(cmd.queueIfUnavailable()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

