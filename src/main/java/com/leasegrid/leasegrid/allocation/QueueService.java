package com.leasegrid.leasegrid.allocation;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class QueueService {
    private final JdbcClient db;
    private final AllocationService allocations;
    private final ObjectMapper json;
    public QueueService(JdbcClient db,AllocationService allocations,ObjectMapper json) {
        this.db=db; this.allocations=allocations; this.json=json;
    }
    public record Head(UUID id,UUID callerId,UUID teamId,int quantity,int durationSeconds,boolean overdue) {}
    @Transactional public void schedule(UUID pool) {
        var capacity=db.sql("select total_capacity-allocated_capacity from resource_pools where id=? and status='ACTIVE' for update")
                .param(pool).query(Integer.class).optional();
        if(capacity.isEmpty()) return;
        var candidate=db.sql("""
                select id,caller_id,team_id,quantity,duration_seconds,coalesce(deadline_at<=clock_timestamp(),false) as overdue
                from allocation_requests where pool_id=? and state='QUEUED' order by created_at,id limit 1 for update
                """).param(pool).query(Head.class).optional();
        if(candidate.isEmpty()) return;
        var h=candidate.get();
        boolean eligible=db.sql("""
                select exists(select 1 from users u join team_memberships m on m.user_id=u.id
                join resource_pools p on p.id=? where u.id=? and m.team_id=? and u.account_status='ACTIVE' and p.max_lease_seconds>=?)
                """).params(pool,h.callerId(),h.teamId(),h.durationSeconds()).query(Boolean.class).single();
        if(h.overdue() || !eligible) {
            db.sql("update allocation_requests set state=? where id=?").params(h.overdue()?"TIMED_OUT":"REJECTED",h.id()).update();
            return;
        }
        int remaining=db.sql("select quota_limit-used_units from team_quotas where team_id=? and pool_id=? for update")
                .params(h.teamId(),pool).query(Integer.class).single();
        if(h.quantity()>capacity.get() || h.quantity()>remaining) return;
        var cmd=new AllocationCommand(pool,h.teamId(),h.quantity(),h.durationSeconds(),true);
        String lease=allocations.grant(h.callerId(),cmd,h.id());
        db.sql("update allocation_requests set state='GRANTED',granted_lease_id=? where id=?")
                .params(UUID.fromString(json.readTree(lease).get("id").asString()),h.id()).update();
    }
    @Transactional public Map<String,Object> request(UUID caller,UUID id,boolean cancel) {
        var rows=db.sql("""
                select r.id as "requestId",state as status,pool_id as "poolId",team_id as "teamId",
                quantity,r.created_at as "createdAt",granted_lease_id as "leaseId"
                from allocation_requests r join users u on u.id=? where r.id=? and u.account_status='ACTIVE'
                and (u.system_role='ADMIN' or r.caller_id=u.id) for update of r
                """).params(caller,id).query().listOfRows();
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var result=new LinkedHashMap<>(rows.get(0));
        if(cancel && !"CANCELLED".equals(result.get("status"))) {
            if(!"QUEUED".equals(result.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
            db.sql("update allocation_requests set state='CANCELLED' where id=?").param(id).update();
            result.put("status","CANCELLED");
        }
        return result;
    }
}
