package com.leasegrid.leasegrid.allocation;

import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/requests/{id}")
public class QueueController {
    private final QueueService queue;
    public QueueController(QueueService queue) { this.queue=queue; }
    @GetMapping public Map<String,Object> get(Principal caller,@PathVariable UUID id) {
        return queue.request(UUID.fromString(caller.getName()),id,false);
    }
    @PostMapping("/cancel") public Map<String,Object> cancel(Principal caller,@PathVariable UUID id) {
        return queue.request(UUID.fromString(caller.getName()),id,true);
    }
}
