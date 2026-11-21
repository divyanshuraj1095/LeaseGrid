package com.leasegrid.leasegrid.lease;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class ExpiryWorker {
    private final JdbcClient db;
    private final LeaseService leases;
    public ExpiryWorker(JdbcClient db,LeaseService leases) { this.db=db; this.leases=leases; }
    @Scheduled(fixedDelayString="${leasegrid.expiration-delay-ms:5000}")
    public void reclaim() {
        var candidates=db.sql("""
                select id from leases where status='ACTIVE' and expires_at<=clock_timestamp()
                order by expires_at,id limit 100
                """).query(UUID.class).list();
        candidates.forEach(leases::expire);
    }
}
