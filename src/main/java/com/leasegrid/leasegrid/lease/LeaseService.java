package com.leasegrid.leasegrid.lease;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeaseService {
    record Identity(UUID team, UUID pool, UUID owner) {}
    public record Lease(UUID id, UUID teamId, UUID poolId, UUID ownerId, int quantity, String status,
            OffsetDateTime issuedAt, OffsetDateTime expiresAt,int renewCount) {}
    private final JdbcClient db;
    public LeaseService(JdbcClient db) { this.db=db; }
    private Identity identity(UUID id) {
        return db.sql("select team_id,pool_id,owner_id from leases where id=?").param(id)
                .query((r,n)->new Identity(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)))
                .optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private Identity authorize(UUID caller,UUID id) {
        var identity=identity(id);
        boolean allowed=db.sql("select true from users where id=? and account_status='ACTIVE' and (id=? or system_role='ADMIN')")
                .params(caller,identity.owner()).query(Boolean.class).optional().orElse(false);
        if(!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return identity;
    }
    private Lease read(UUID id,boolean lock) {
        return db.sql("""
                select id,team_id,pool_id,owner_id,quantity,status,issued_at,expires_at,renew_count
                from leases where id=?
                """+(lock?" for update":"")).param(id).query(Lease.class).single();
    }
    public Lease get(UUID caller,UUID id) { authorize(caller,id); return read(id,false); }
    private int lockPool(Identity identity) {
        return db.sql("select max_lease_seconds from resource_pools where id=? for update")
                .param(identity.pool()).query(Integer.class).single();
    }
    private OffsetDateTime now() { return db.sql("select clock_timestamp()").query(OffsetDateTime.class).single(); }
    @Transactional public Lease renew(UUID caller,UUID id,OffsetDateTime target) {
        var identity=authorize(caller,id);
        int maximum=lockPool(identity);
        var lease=read(id,true);
        if(!lease.status().equals("ACTIVE") || !lease.expiresAt().isAfter(now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"LEASE_NOT_ACTIVE");
        if(target.isAfter(lease.issuedAt().plusSeconds(maximum)))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"LEASE_DURATION_LIMIT");
        if(target.isBefore(lease.expiresAt())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expiry precedes current deadline");
        if(target.isAfter(lease.expiresAt()))
            db.sql("update leases set expires_at=?,renew_count=renew_count+1 where id=?").params(target,id).update();
        return read(id,false);
    }
    @Transactional public Lease release(UUID caller,UUID id) { return end(id,authorize(caller,id),false); }
    @Transactional public void expire(UUID id) { end(id,identity(id),true); }
    @Transactional public Lease revoke(UUID caller,UUID id,String reason) {
        if(!db.sql("select true from users where id=? and system_role='ADMIN' and account_status='ACTIVE'").param(caller)
                .query(Boolean.class).optional().orElse(false)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        db.sql("select set_config('leasegrid.actor',?,true),set_config('leasegrid.reason',?,true)")
                .params(caller.toString(),reason).query().singleRow();
        return end(id,identity(id),false,"REVOKED");
    }
    private Lease end(UUID id,Identity identity,boolean expiryOnly) { return end(id,identity,expiryOnly,"RELEASED"); }
    private Lease end(UUID id,Identity identity,boolean expiryOnly,String terminal) {
        lockPool(identity);
        db.sql("select used_units from team_quotas where team_id=? and pool_id=? for update")
                .params(identity.team(),identity.pool()).query(Integer.class).single();
        var lease=read(id,true);
        var instant=now();
        boolean overdue=!lease.expiresAt().isAfter(instant);
        if(!lease.status().equals("ACTIVE") || (expiryOnly && !overdue)) return lease;
        db.sql("update leases set status=?,ended_at=? where id=?").params(overdue?"EXPIRED":terminal,instant,id).update();
        db.sql("update resource_pools set allocated_capacity=allocated_capacity-?,updated_at=now() where id=?")
                .params(lease.quantity(),identity.pool()).update();
        db.sql("update team_quotas set used_units=used_units-?,updated_at=now() where team_id=? and pool_id=?")
                .params(lease.quantity(),identity.team(),identity.pool()).update();
        return read(id,false);
    }
}

