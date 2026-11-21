package com.leasegrid.leasegrid.management;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ManagementService {
    private final JdbcClient db;
    public ManagementService(JdbcClient db) { this.db=db; }
    public Map<String,Object> user(UUID caller) {
        return db.sql("""
                select id,email,display_name as "displayName",system_role as "systemRole" from users
                where id=? and account_status='ACTIVE'
                """).param(caller).query().listOfRows().stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    private boolean admin(UUID caller) { return user(caller).get("systemRole").equals("ADMIN"); }
    private void requireAdmin(UUID caller) {
        if(!admin(caller)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public Map<String,Object> team(UUID caller,String name) {
        requireAdmin(caller);
        var team=db.sql("insert into teams(name,created_by) values(?,?) returning id,name").params(name,caller).query().singleRow();
        member(caller,(UUID)team.get("id"),caller,"OWNER");
        return team;
    }
    public Map<String,Object> member(UUID caller,UUID team,UUID user,String role) {
        if(!admin(caller) && db.sql("select true from team_memberships where team_id=? and user_id=? and team_role in ('OWNER','ADMIN')")
                .params(team,caller).query(Boolean.class).optional().isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return db.sql("""
                insert into team_memberships(team_id,user_id,team_role) values(?,?,?)
                returning user_id as "userId",team_role as "teamRole"
                """).params(team,user,role).query().singleRow();
    }
    public Map<String,Object> pool(UUID caller,ManagementController.Pool p) {
        requireAdmin(caller);
        return db.sql("""
                insert into resource_pools(code,name,resource_type,total_capacity,max_lease_seconds) values(?,?,?,?,?)
                returning id,code,name,resource_type as "resourceType",total_capacity as "totalCapacity",
                max_lease_seconds as "maxLeaseSeconds",allocated_capacity as "allocatedCapacity",
                total_capacity-allocated_capacity as "availableCapacity",status
                """).params(p.code(),p.name(),p.resourceType(),p.totalCapacity(),p.maxLeaseSeconds()).query().singleRow();
    }
    public Map<String,Object> overview(UUID caller) {
        requireAdmin(caller);
        return db.sql("""
                select (select count(*) from resource_pools) as "poolCount",
                (select coalesce(sum(total_capacity),0) from resource_pools) as "totalCapacity",
                (select coalesce(sum(allocated_capacity),0) from resource_pools) as "allocatedCapacity",
                (select count(*) from leases where status='ACTIVE' and expires_at>clock_timestamp()) as "activeLeases",
                (select count(*) from allocation_requests where state='QUEUED') as "queuedRequests"
                """).query().singleRow();
    }
    public Map<String,Object> readQuota(UUID caller,UUID team,UUID pool) {
        boolean allowed=admin(caller) || db.sql("select true from team_memberships where user_id=? and team_id=?")
                .params(caller,team).query(Boolean.class).optional().orElse(false);
        if(!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return db.sql("""
                select team_id as "teamId",pool_id as "poolId",quota_limit as "quotaLimit",used_units as "usedUnits",
                quota_limit-used_units as "remainingUnits" from team_quotas where team_id=? and pool_id=?
                """).params(team,pool).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public Map<String,Object> patch(UUID caller,UUID pool,ManagementController.PoolPatch patch) {
        requireAdmin(caller);
        return db.sql("""
                update resource_pools set name=coalesce(?,name),status=coalesce(?,status),
                total_capacity=coalesce(?,total_capacity),max_lease_seconds=coalesce(?,max_lease_seconds),updated_at=now()
                where id=? returning id,code,name,resource_type as "resourceType",total_capacity as "totalCapacity",
                allocated_capacity as "allocatedCapacity",total_capacity-allocated_capacity as "availableCapacity",
                max_lease_seconds as "maxLeaseSeconds",status
                """).params(patch.name(),patch.status(),patch.totalCapacity(),patch.maxLeaseSeconds(),pool)
                .query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public Map<String,Object> quota(UUID caller,UUID team,UUID pool,int limit) {
        requireAdmin(caller);
        return db.sql("""
                insert into team_quotas(team_id,pool_id,quota_limit) values(?,?,?)
                on conflict(team_id,pool_id) do update set quota_limit=excluded.quota_limit,updated_at=now()
                where team_quotas.used_units<=excluded.quota_limit
                returning team_id as "teamId",pool_id as "poolId",quota_limit as "quotaLimit",used_units as "usedUnits",
                quota_limit-used_units as "remainingUnits"
                """).params(team,pool,limit).query().listOfRows().stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Quota below current usage"));
    }
}

