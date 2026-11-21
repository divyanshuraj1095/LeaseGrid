package com.leasegrid.leasegrid.management;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogService {
    enum Catalog {
        TEAMS("teams t","t.id,t.name","t.created_at","t.id","exists(select 1 from team_memberships m where m.team_id=t.id and m.user_id=:caller)"),
        POOLS("resource_pools p","p.id,p.code,p.name,p.resource_type as \"resourceType\",p.total_capacity as \"totalCapacity\",p.allocated_capacity as \"allocatedCapacity\",p.total_capacity-p.allocated_capacity as \"availableCapacity\",p.status,p.max_lease_seconds as \"maxLeaseSeconds\"","p.created_at","p.id","exists(select 1 from team_memberships m join team_quotas q on q.team_id=m.team_id where q.pool_id=p.id and m.user_id=:caller)"),
        LEASES("leases l","l.id,l.team_id as \"teamId\",l.pool_id as \"poolId\",l.owner_id as \"ownerId\",l.quantity,l.status,l.issued_at as \"issuedAt\",l.expires_at as \"expiresAt\",l.renew_count as \"renewCount\"","l.issued_at","l.id","l.owner_id=:caller"),
        MEMBERS("team_memberships t","t.user_id as \"userId\",t.team_role as \"teamRole\"","t.joined_at","t.user_id","exists(select 1 from team_memberships m where m.team_id=t.team_id and m.user_id=:caller)"),
        AUDIT("audit_events a","a.id,a.actor_id as \"actorId\",a.entity_type as \"entityType\",a.entity_id as \"entityId\",a.event_type as \"eventType\",a.occurred_at as \"occurredAt\",a.details::text as details","a.occurred_at","a.id","false");
        final String table,columns,time,id,visible;
        Catalog(String table,String columns,String time,String id,String visible) {
            this.table=table; this.columns=columns; this.time=time; this.id=id; this.visible=visible;
        }
    }
    private final JdbcClient db;
    private final ManagementService management;
    public CatalogService(JdbcClient db,ManagementService management) { this.db=db; this.management=management; }
    Map<String,Object> page(UUID caller,Catalog c,UUID team,Integer limit,String cursor,UUID item) {
        if(limit<1 || limit>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        boolean admin=management.user(caller).get("systemRole").equals("ADMIN");
        if(c==Catalog.AUDIT && !admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String sql="select "+c.columns+","+c.time+"::text as pagination_time,"+c.id+" as pagination_id from "+c.table+" where ";
        var args=new HashMap<String,Object>();
        sql+=admin?"true":c.visible;
        if(!admin) args.put("caller",caller);
        if(team!=null) {
            sql+=" and "+(c==Catalog.MEMBERS?"t.team_id":"l.team_id")+"=:team";
            args.put("team",team);
        }
        if(item!=null) { sql+=" and "+c.id+"=:item";args.put("item",item); }
        String scope=c+"/"+caller+"/"+team+"/";
        if(cursor!=null) {
            try {
                String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
                if(!decoded.startsWith(scope)) throw new IllegalArgumentException();
                String[] parts=decoded.substring(scope.length()).split("/");
                if(parts.length!=2) throw new IllegalArgumentException();
                args.put("lastTime",java.time.OffsetDateTime.parse(parts[0].replace(' ','T')));
                args.put("lastId",UUID.fromString(parts[1]));
            } catch(RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid cursor"); }
            sql+=" and ("+c.time+","+c.id+")<(:lastTime,:lastId)";
        }
        sql+=" order by "+c.time+" desc,"+c.id+" desc limit :limit";
        args.put("limit",limit+1);
        var rows=db.sql(sql).params(args).query().listOfRows();
        var items=new ArrayList<Map<String,Object>>();
        String next=null;
        for(var row:rows.subList(0,Math.min(limit,rows.size()))) {
            var value=new LinkedHashMap<>(row);
            Object timestamp=value.remove("pagination_time"),id=value.remove("pagination_id");
            next=Base64.getUrlEncoder().withoutPadding().encodeToString((scope+timestamp+"/"+id).getBytes(StandardCharsets.UTF_8));
            items.add(value);
        }
        if(item!=null) return items.stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return rows.size()>limit?Map.of("items",items,"nextCursor",next):Map.of("items",items);
    }
}
