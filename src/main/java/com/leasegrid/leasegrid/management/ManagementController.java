package com.leasegrid.leasegrid.management;

import java.security.Principal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1")
public class ManagementController {
    public record Team(@NotBlank @Size(max=160) String name) {}
    public record Member(@NotNull UUID userId,@NotNull @Pattern(regexp="OWNER|ADMIN|MEMBER") String teamRole) {}
    public record Pool(@NotBlank @Size(max=80) String code,@NotBlank @Size(max=160) String name,
            @NotNull @Pattern(regexp="GPU|CPU_SLOT|TEST_ENV|GENERIC") String resourceType,
            @Positive int totalCapacity,@Min(60) @Max(604800) int maxLeaseSeconds) {}
    public record PoolPatch(@Size(min=1,max=160) String name,@Pattern(regexp="ACTIVE|DRAINING|DISABLED") String status,
            @Positive Integer totalCapacity,@Min(60) @Max(604800) Integer maxLeaseSeconds) {}
    @PatchMapping("/pools/{pool}") public Map<String,Object> patch(Principal p,@PathVariable UUID pool,@Valid @RequestBody PoolPatch patch) {
        return management.patch(caller(p),pool,patch);
    }
    @GetMapping("/teams/{team}/quotas/{pool}") public Map<String,Object> quota(Principal p,@PathVariable UUID team,@PathVariable UUID pool) {
        return management.readQuota(caller(p),team,pool);
    }
    @GetMapping("/analytics/overview") public Map<String,Object> overview(Principal p) { return management.overview(caller(p)); }
    public record Quota(@NotNull @PositiveOrZero Integer quotaLimit) {}
    private final ManagementService management;
    public ManagementController(ManagementService management) { this.management=management; }
    private UUID caller(Principal principal) { return UUID.fromString(principal.getName()); }
    @GetMapping("/auth/me") public Map<String,Object> me(Principal principal) { return management.user(caller(principal)); }
    @PostMapping("/teams") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> team(Principal principal,@Valid @RequestBody Team body) { return management.team(caller(principal),body.name()); }
    @PostMapping("/teams/{team}/members") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> member(Principal principal,@PathVariable UUID team,@Valid @RequestBody Member body) {
        return management.member(caller(principal),team,body.userId(),body.teamRole());
    }
    @PostMapping("/pools") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> pool(Principal principal,@Valid @RequestBody Pool body) { return management.pool(caller(principal),body); }
    @PutMapping("/teams/{team}/quotas/{pool}") public Map<String,Object> quota(Principal principal,@PathVariable UUID team,
            @PathVariable UUID pool,@Valid @RequestBody Quota body) {
        return management.quota(caller(principal),team,pool,body.quotaLimit());
    }
}

