package com.leasegrid.leasegrid.lease;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leases/{id}")
public class LeaseController {
    public record Revocation(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1000) String reason) {}
    @PostMapping("/revoke") public LeaseService.Lease revoke(Principal caller,@PathVariable UUID id,@RequestBody @Valid Revocation request) {
        return leases.revoke(UUID.fromString(caller.getName()),id,request.reason());
    }
    public record Renewal(@NotNull OffsetDateTime requestedExpiresAt) {}
    private final LeaseService leases;
    public LeaseController(LeaseService leases) { this.leases=leases; }
    @GetMapping public LeaseService.Lease get(Principal caller,@PathVariable UUID id) {
        return leases.get(UUID.fromString(caller.getName()),id);
    }
    @PostMapping("/release") public LeaseService.Lease release(Principal caller,@PathVariable UUID id) {
        return leases.release(UUID.fromString(caller.getName()),id);
    }
    @PostMapping("/renew") public LeaseService.Lease renew(Principal caller,@PathVariable UUID id,@RequestBody @Valid Renewal request) {
        return leases.renew(UUID.fromString(caller.getName()),id,request.requestedExpiresAt());
    }
}

