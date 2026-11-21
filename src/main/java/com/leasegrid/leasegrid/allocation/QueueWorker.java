package com.leasegrid.leasegrid.allocation;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QueueWorker {
    private final JdbcClient db;
    private final QueueService queue;
    public QueueWorker(JdbcClient db,QueueService queue) { this.db=db; this.queue=queue; }
    @Scheduled(fixedDelayString="${leasegrid.queue-delay-ms:1000}")
    public void run() {
        db.sql("select distinct pool_id from allocation_requests where state='QUEUED'")
                .query(UUID.class).list().forEach(queue::schedule);
    }
}
