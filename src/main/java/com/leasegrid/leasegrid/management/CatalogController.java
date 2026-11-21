package com.leasegrid.leasegrid.management;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.leasegrid.leasegrid.management.CatalogService.Catalog.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog=catalog; }
    private UUID caller(Principal p) { return UUID.fromString(p.getName()); }
    @GetMapping("/teams") public Map<String,Object> teams(Principal p,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return catalog.page(caller(p),TEAMS,null,limit,cursor,null);
    }
    @GetMapping("/teams/{id}") public Map<String,Object> team(Principal p,@PathVariable UUID id) {
        return catalog.page(caller(p),TEAMS,null,1,null,id);
    }
    @GetMapping("/teams/{id}/members") public Map<String,Object> members(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        catalog.page(caller(p),TEAMS,null,1,null,id);
        return catalog.page(caller(p),MEMBERS,id,limit,cursor,null);
    }
    @GetMapping("/pools") public Map<String,Object> pools(Principal p,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return catalog.page(caller(p),POOLS,null,limit,cursor,null);
    }
    @GetMapping("/pools/{id}") public Map<String,Object> pool(Principal p,@PathVariable UUID id) {
        return catalog.page(caller(p),POOLS,null,1,null,id);
    }
    @GetMapping("/leases") public Map<String,Object> leases(Principal p,@RequestParam(required=false) UUID teamId,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return catalog.page(caller(p),LEASES,teamId,limit,cursor,null);
    }
    @GetMapping("/audit-events") public Map<String,Object> audit(Principal p,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return catalog.page(caller(p),AUDIT,null,limit,cursor,null);
    }
}
