package com.leasegrid.leasegrid;

import com.leasegrid.leasegrid.allocation.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeasegridApplicationTests {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres local;
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        if (Boolean.getBoolean("leasegrid.test.embedded")) {
            try { local=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.start(); }
            catch(java.io.IOException e) { throw new IllegalStateException(e); }
            properties.add("spring.datasource.url",()->local.getJdbcUrl("postgres","postgres"));
            properties.add("spring.datasource.username",()->"postgres");
            properties.add("spring.datasource.password",()->"");
        } else {
        postgres.start();
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
        }
        properties.add("JWT_SECRET",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @AfterAll static void stop(@Autowired org.springframework.context.ConfigurableApplicationContext context) throws Exception { context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).destroy(); if(local!=null) local.close(); else postgres.stop(); }
        @Autowired com.leasegrid.leasegrid.lease.LeaseService leases;
    @Test void renewalAndRepeatRelease() {
        allocations.allocate(caller,"lease",new AllocationCommand(pool,team,1,60,false));
        var id=db.sql("select id from leases where pool_id=?").param(pool).query(UUID.class).single();
        var lease=leases.get(caller,id);
        var target=lease.issuedAt().plusSeconds(300);
        assertEquals(1,leases.renew(caller,id,target).renewCount());
        assertEquals(1,leases.renew(caller,id,target).renewCount());
        var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->leases.renew(caller,id,lease.issuedAt().plusSeconds(3601)));
        assertEquals(422,error.getStatusCode().value());
        assertEquals("RELEASED",leases.release(caller,id).status());
        assertEquals("RELEASED",leases.release(caller,id).status());
        assertEquals(0,db.sql("select used_units from team_quotas where pool_id=?").param(pool).query(Integer.class).single());
    }
    @Test void overdueLeaseCannotBeRevived() {
        allocations.allocate(caller,"expire",new AllocationCommand(pool,team,1,60,false));
        var id=db.sql("update leases set issued_at=now()-interval '2 minutes', expires_at=now()-interval '1 minute' where pool_id=? returning id")
                .param(pool).query(UUID.class).single();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->leases.renew(caller,id,java.time.OffsetDateTime.now().plusMinutes(1)));
        leases.expire(id); leases.expire(id);
        assertEquals("EXPIRED",leases.get(caller,id).status());
        assertEquals(0,db.sql("select allocated_capacity from resource_pools where id=?").param(pool).query(Integer.class).single());
    }
        @Autowired QueueService queue;
    @Test void strictFifoAndCancellation() {
        allocations.allocate(caller,"holding",new AllocationCommand(pool,team,9,600,false));
        var wait=allocations.allocate(caller,"waiting",new AllocationCommand(pool,team,2,600,true));
        assertEquals(202,wait.status());
        assertEquals(409,allocations.allocate(caller,"cannot-bypass",new AllocationCommand(pool,team,1,600,false)).status());
        var head=db.sql("select id from allocation_requests where pool_id=?").param(pool).query(UUID.class).single();
        queue.schedule(pool);
        assertEquals("QUEUED",queue.request(caller,head,false).get("status"));
        var holding=db.sql("select id from leases where pool_id=?").param(pool).query(UUID.class).single();
        leases.release(caller,holding);
        queue.schedule(pool);
        assertEquals("GRANTED",queue.request(caller,head,false).get("status"));
        allocations.allocate(caller,"cancel",new AllocationCommand(pool,team,10,600,true));
        var tail=db.sql("select id from allocation_requests where pool_id=? and state='QUEUED'").param(pool).query(UUID.class).single();
        assertEquals("CANCELLED",queue.request(caller,tail,true).get("status"));
        assertEquals("CANCELLED",queue.request(caller,tail,true).get("status"));
    }
    @Test void concurrentIdenticalKeysCreateOneLease() throws Exception {
        var command=new AllocationCommand(pool,team,1,600,false);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<Callable<AllocationService.Result>>();
            for(int i=0;i<100;i++) jobs.add(()->allocations.allocate(caller,"same",command));
            int original=0;
            for(var future:executor.invokeAll(jobs)) {
                var response=future.get();
                assertEquals(201,response.status());
                if(!response.replayed()) original++;
            }
            assertEquals(1,original);
        }
        assertEquals(1,db.sql("select count(*) from leases where pool_id=?").param(pool).query(Integer.class).single());
        assertEquals(409,allocations.allocate(caller,"same",new AllocationCommand(pool,team,2,600,false)).status());
    }
        @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Test void registerLoginAndReadIdentityOverHttp() throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();
        var root="http://localhost:"+port+"/api/v1/auth/";
        String origin = "http://localhost:5173";
        var preflight = java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"login"))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        var cors = client.send(preflight, java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(200, cors.statusCode());
        assertEquals(origin, cors.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        String email=UUID.randomUUID()+"@example.org";
        var mapper=new tools.jackson.databind.ObjectMapper();
        String registration=mapper.writeValueAsString(Map.of("email",email,"password","fixture-password","displayName","API tester"));
        var signup=java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"register")).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(registration)).build();
        assertEquals(201,client.send(signup,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var login=java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"login")).header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email",email,"password","fixture-password")))).build();
        var token=client.send(login,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,token.statusCode());
        var me=java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"me"))
                .header("Origin",origin).header("Authorization","Bearer "+mapper.readTree(token.body()).get("accessToken").asString()).GET().build();
        var profile=client.send(me,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,profile.statusCode());
        assertEquals(origin,profile.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals(email,mapper.readTree(profile.body()).get("email").asString());
    }
        @Test void rollbackRemovesLeaseCountersAndKeyClaim() {
        var template=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var cmd=new AllocationCommand(pool,team,1,600,false);
        template.executeWithoutResult(transaction->{
            assertEquals(201,allocations.allocate(caller,"crash",cmd).status());
            transaction.setRollbackOnly();
        });
        assertEquals(0,db.sql("select allocated_capacity from resource_pools where id=?").param(pool).query(Integer.class).single());
        assertEquals(0,db.sql("select count(*) from leases where pool_id=?").param(pool).query(Integer.class).single());
        assertFalse(allocations.allocate(caller,"crash",cmd).replayed());
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired AllocationService allocations;
    @Autowired JdbcClient db;
    UUID caller, team, pool;
    @BeforeEach void seed() {
        caller=db.sql("insert into users(email,display_name,password_hash) values(?,'Owner','unused') returning id")
                .param(UUID.randomUUID()+"@example.org").query(UUID.class).single();
        team=db.sql("insert into teams(name,created_by) values(?,?) returning id").params(UUID.randomUUID().toString(),caller).query(UUID.class).single();
        db.sql("insert into team_memberships(team_id,user_id,team_role) values(?,?,'OWNER')").params(team,caller).update();
        pool=db.sql("insert into resource_pools(code,name,resource_type,total_capacity,max_lease_seconds) values(?,'GPU','GPU',10,3600) returning id")
                .param(UUID.randomUUID().toString()).query(UUID.class).single();
        db.sql("insert into team_quotas(team_id,pool_id,quota_limit) values(?,?,100)").params(team,pool).update();
    }
    @Test void simultaneousRequestsConserveCapacity() throws Exception {
        var start=new CountDownLatch(1);
        var ready=new CountDownLatch(100);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var responses=new ArrayList<Future<Integer>>();
            for(int i=0;i<100;i++) responses.add(executor.submit(()->{
                ready.countDown(); start.await(30,TimeUnit.SECONDS);
                return allocations.allocate(caller,UUID.randomUUID().toString(),new AllocationCommand(pool,team,1,600,false)).status();
            }));
            assertTrue(ready.await(30,TimeUnit.SECONDS)); start.countDown();
            int granted=0;
            for(var response:responses) {
                int status=response.get(60,TimeUnit.SECONDS);
                if(status==201) granted++; else assertEquals(409,status);
            }
            assertEquals(10,granted);
        }
        assertEquals(10,db.sql("select allocated_capacity from resource_pools where id=?").param(pool).query(Integer.class).single());
        assertEquals(10,db.sql("select used_units from team_quotas where pool_id=?").param(pool).query(Integer.class).single());
        assertEquals(10,db.sql("select sum(quantity) from leases where pool_id=?").param(pool).query(Integer.class).single());
    }
    @Test void replayKeepsOriginalDenial() {
        db.sql("update team_quotas set quota_limit=0 where pool_id=?").param(pool).update();
        var command=new AllocationCommand(pool,team,1,600,false);
        var denied=allocations.allocate(caller,"retry",command);
        assertEquals(409,denied.status());
        db.sql("update team_quotas set quota_limit=5 where pool_id=?").param(pool).update();
        var replay=allocations.allocate(caller,"retry",command);
        assertTrue(replay.replayed()); assertEquals(409,replay.status());
        assertTrue(replay.body().contains("QUOTA_EXCEEDED"));
        assertEquals(201,allocations.allocate(caller,"fresh",command).status());
    }
}






