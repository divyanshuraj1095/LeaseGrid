package com.leasegrid.leasegrid.allocation;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/allocations")
public class AllocationController {
    private final AllocationService service;
    private final ObjectMapper json;
    public AllocationController(AllocationService service,ObjectMapper json) {
        this.service=service; this.json=json;
    }
    @PostMapping public ResponseEntity<String> allocate(Principal caller,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AllocationCommand command) {
        var result=service.allocate(UUID.fromString(caller.getName()),key,command);
        var response=ResponseEntity.status(result.status());
        if(result.replayed()) response.header("Idempotency-Replayed","true");
        if(result.status()==201) response.header("Location","/api/v1/leases/"+json.readTree(result.body()).get("id").asString());
        if(result.status()==202) response.header("Location","/api/v1/requests/"+json.readTree(result.body()).get("requestId").asString());
        return response.contentType(result.status()<300?MediaType.APPLICATION_JSON:MediaType.APPLICATION_PROBLEM_JSON).body(result.body());
    }
}

