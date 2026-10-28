package com.unicarona.job;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.job.repository.OutboxDeliveryRepository;
import com.unicarona.job.service.OutboxProcessor;
import com.unicarona.notification.provider.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

class JobHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static OutboxProcessor processor;
    private static OutboxDeliveryRepository deliveries;
    private static TrackingEmail provider;
    private static TransactionTemplate tx;
    private static CryptoService crypto;
    private static String server,passwordHash;
    private static UUID university,course,classId;
    private static final String PASSWORD="Synthetic outbox password 2026";
    @TempDir static Path mailbox;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @TestConfiguration(proxyBeanMethods=false) static class ProviderTestConfig {
        @Bean TrackingEmail trackingEmail(ObjectMapper json) { return new TrackingEmail(new DevelopmentEmailProvider(mailbox,mailbox.resolve("public"),json)); }
    }
    static final class TrackingEmail implements EmailProvider {
        enum Mode { NORMAL, FAIL, BLOCK }
        final EmailProvider delegate;
        final AtomicReference<Mode> mode=new AtomicReference<>(Mode.NORMAL);
        final List<Message> messages=new CopyOnWriteArrayList<>();
        final AtomicInteger calls=new AtomicInteger();
        volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        TrackingEmail(EmailProvider delegate) { this.delegate=delegate; }
        @Override public void send(Message input) throws Exception {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();messages.add(input);entered.countDown();
            if(mode.get()==Mode.BLOCK && !release.await(10,TimeUnit.SECONDS)) throw new IOException("Synthetic bounded wait");
            if(mode.get()==Mode.FAIL) throw new IOException("Synthetic provider error with private content which must never be stored");
            delegate.send(input);
        }
        void reset() { mode.set(Mode.NORMAL);calls.set(0);messages.clear();entered=new CountDownLatch(0);release=new CountDownLatch(0); }
    }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.add("--DEV_MAIL_PATH="+mailbox);
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,ProviderTestConfig.class).run(args.toArray(String[]::new));
        sql=app.getBean(JdbcTemplate.class);processor=app.getBean(OutboxProcessor.class);deliveries=app.getBean(OutboxDeliveryRepository.class);provider=app.getBean(TrackingEmail.class);crypto=app.getBean(CryptoService.class);
        assertEquals(1,app.getBeansOfType(EmailProvider.class).size());tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=crypto.hashPassword(PASSWORD);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('job-university','Job University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'job-course','Job Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,1,'A') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void reset() { sql.update("DELETE FROM rate_limits");sql.update("DELETE FROM outbox_jobs");provider.reset(); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private String key() { return "synthetic-job-"+UUID.randomUUID(); }
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(session!=null) request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());if(key!=null) request.header("idempotency-key",key);
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else request.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void code(HttpResponse<String> response,int status,String code) throws Exception { assertEquals(status,response.statusCode());assertEquals(code,tree(response).path("code").asText()); }
    private Session user(boolean admin) throws Exception {
        String email="job-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,role,email_verified_at) VALUES(?,?,'Synthetic Job','2000-05-01',?,now()) RETURNING id",UUID.class,email,passwordHash,admin?"admin":"user");
        if(!admin) sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',1,'night','Gama')",id,university,course,classId);
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")) {var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private UUID job(String type,Object payload,String status,int attempts) throws Exception {
        return sql.queryForObject("INSERT INTO outbox_jobs(type,event_key,payload,status,attempts) VALUES(?,?,?::jsonb,?,?) RETURNING id",UUID.class,type,key(),json.writeValueAsString(payload),status,attempts);
    }
    private UUID email(String status,int attempts) throws Exception {
        return job("email",Map.of("to","synthetic-recipient@example.test","kind","verify_email","urlCiphertext",crypto.encrypt("https://example.test/verify?token=synthetic-local-only",AuthTestSettings.KEY)),status,attempts);
    }
    private String state(UUID id) { return sql.queryForObject("SELECT status FROM outbox_jobs WHERE id=?",String.class,id); }
    private int attempts(UUID id) { return sql.queryForObject("SELECT attempts FROM outbox_jobs WHERE id=?",Integer.class,id); }
    private String retryPath(Object id) { return "/api/v1/admin/jobs/"+id+"/retry"; }
    private Map<String,Object> retry(int attempts) { return Map.of("expectedAttempts",attempts,"reason"," Synthetic justified retry "); }
    private Map<String,String> headers(Session session,String key) {
        var headers=new LinkedHashMap<String,String>();headers.put("origin",AuthTestSettings.ORIGIN);headers.put("cookie",session.cookie());headers.put("x-csrf-token",session.csrf());if(key!=null) headers.put("idempotency-key",key);return headers;
    }
    @Test void emailDeliveryDecryptsOnlyOutsideTransactionAndWritesStablePrivateLocalFile() throws Exception {
        UUID id=email("pending",0);assertTrue(processor.processOne());assertEquals("done",state(id));assertEquals(1,attempts(id));assertFalse(processor.processOne());assertEquals(1,provider.calls.get());
        var stored=json.readTree(Files.readAllBytes(mailbox.resolve(id+".json")));assertEquals(id.toString(),stored.path("id").asText());assertEquals("verify_email",stored.path("kind").asText());assertEquals("https://example.test/verify?token=synthetic-local-only",stored.path("url").asText());
        assertTrue(sql.queryForObject("SELECT completed_at IS NOT NULL AND leased_until IS NULL AND NOT jsonb_exists(payload,'url') FROM outbox_jobs WHERE id=?",Boolean.class,id));
    }
    @Test void actualForgotPasswordOutboxIsDeliveredWithoutPuttingPlaintextLinkInDatabase() throws Exception {
        var user=user(false);String email=sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,user.id());assertEquals(200,send("POST","/api/v1/auth/forgot-password",Map.of("email",email),null,null).statusCode());
        UUID id=sql.queryForObject("SELECT o.id FROM outbox_jobs o JOIN auth_tokens t ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind='reset_password'",UUID.class,user.id());assertTrue(processor.processOne());
        var message=json.readTree(Files.readAllBytes(mailbox.resolve(id+".json")));assertEquals(email,message.path("to").asText());assertEquals("reset_password",message.path("kind").asText());assertTrue(message.path("url").asText().startsWith(AuthTestSettings.ORIGIN+"/login.html#redefinir?token="));
        assertFalse(sql.queryForObject("SELECT payload::text LIKE ? FROM outbox_jobs WHERE id=?",Boolean.class,"%"+message.path("url").asText()+"%",id));
    }
    @Test void failureBackoffReachesDeadAtEighthAttemptAndStoresOnlyPublicErrorName() throws Exception {
        UUID id=email("pending",0);provider.mode.set(TrackingEmail.Mode.FAIL);
        for(int attempt=1;attempt<=8;attempt++) {
            assertTrue(processor.processOne());assertEquals(attempt,attempts(id));assertEquals(attempt==8?"dead":"pending",state(id));
            assertEquals("Error",sql.queryForObject("SELECT last_error_code FROM outbox_jobs WHERE id=?",String.class,id));
            double delay=sql.queryForObject("SELECT extract(epoch FROM available_at-now()) FROM outbox_jobs WHERE id=?",Double.class,id);assertTrue(delay>Math.pow(2,attempt)-2 && delay<=Math.pow(2,attempt));
            assertFalse(processor.processOne());sql.update("UPDATE outbox_jobs SET available_at=now()-interval '1 second' WHERE id=?",id);
        }
        assertEquals(8,provider.calls.get());assertFalse(processor.processOne());
    }
    @Test void malformedCiphertextAndUnknownJobTypeAreFailedWithoutLeakingPayload() throws Exception {
        UUID malformed=job("email",Map.of("to","private@example.test","kind","verify_email","urlCiphertext","invalid"),"pending",0);UUID unsupported=job("external-private-type",Map.of("url","private secret"),"pending",7);
        assertTrue(processor.processOne());assertTrue(processor.processOne());assertEquals("pending",state(malformed));assertEquals("dead",state(unsupported));assertEquals(0,provider.calls.get());
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE id IN (?,?) AND last_error_code='Error'",Integer.class,malformed,unsupported));
    }
    @Test void expiredLeaseIsReclaimedAndStaleCallbacksCannotOverwriteNewAttempt() throws Exception {
        UUID id=email("pending",0);var stale=deliveries.claim().orElseThrow();assertEquals(id,stale.id());assertTrue(deliveries.claim().isEmpty());
        sql.update("UPDATE outbox_jobs SET leased_until=now()-interval '1 second' WHERE id=?",id);var fresh=deliveries.claim().orElseThrow();assertEquals(2,fresh.attempt());
        deliveries.done(stale);deliveries.failed(stale,"Error");assertEquals("processing",state(id));assertEquals(2,attempts(id));deliveries.done(fresh);assertEquals("done",state(id));
    }
    @Test void skipLockedAllowsAnotherWorkerToClaimWhileFirstRowIsLocked() throws Exception {
        UUID first=email("pending",0),second=email("pending",0);sql.update("UPDATE outbox_jobs SET available_at=now()-interval '2 minutes' WHERE id=?",first);
        try(var connection=database.dataSource().getConnection()) {
            connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM outbox_jobs WHERE id=? FOR UPDATE")) {lock.setObject(1,first);lock.executeQuery().close();}
            var claim=deliveries.claim().orElseThrow();assertEquals(second,claim.id());assertEquals("pending",state(first));deliveries.done(claim);connection.rollback();
        }
        assertTrue(processor.processOne());assertEquals("done",state(first));
    }
    @Test void concurrentWorkersDeliverDifferentJobsAndNeverHoldNetworkTransactions() throws Exception {
        UUID first=email("pending",0),second=email("pending",0);provider.mode.set(TrackingEmail.Mode.BLOCK);provider.entered=new CountDownLatch(2);provider.release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(processor::processOne);var b=executor.submit(processor::processOne);
            try { assertTrue(provider.entered.await(5,TimeUnit.SECONDS));assertEquals("processing",state(first));assertEquals("processing",state(second));assertFalse(processor.processOne()); }
            finally { provider.release.countDown(); }
            assertTrue(a.get(5,TimeUnit.SECONDS));assertTrue(b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(Set.of(first,second),new HashSet<>(provider.messages.stream().map(EmailProvider.Message::id).toList()));assertEquals(2,provider.calls.get());assertEquals("done",state(first));assertEquals("done",state(second));
    }
    @Test void processorRejectsAnAmbientTransactionBeforeClaimOrNetwork() throws Exception {
        UUID id=email("pending",0);assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,() -> tx.executeWithoutResult(status -> processor.processOne()));assertEquals(0,attempts(id));assertEquals(0,provider.calls.get());
    }
    @Test void transferOutboxSubmitsStableReferenceButWaitsForSignedSettlement() throws Exception {
        var user=user(false);var ledger=app.getBean(LedgerService.class);tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer(key(),"synthetic_funding","Fixture only",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-600),new Posting(user.id(),AccountKind.PASSENGER_CREDIT,600)))));
        var response=send("POST","/api/v1/transfers",Map.of("type","refund","cents",400),user,key());assertEquals(200,response.statusCode());UUID transfer=UUID.fromString(tree(response).path("id").asText());UUID job=sql.queryForObject("SELECT id FROM outbox_jobs WHERE event_key=?",UUID.class,"transfer:"+transfer);
        assertTrue(processor.processOne());assertEquals("done",state(job));assertEquals("dev_transfer_"+transfer,sql.queryForObject("SELECT provider_reference FROM transfers WHERE id=?",String.class,transfer));assertEquals("pending",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,transfer));assertEquals(400,ledger.balance(user.id(),AccountKind.REFUND_PENDING));assertEquals(0,ledger.balance(user.id(),AccountKind.REFUNDED));
    }
    @Test void administrativeQueueIsPaginatedOrderedAndNeverExposesPayloadOrUnsafeError() throws Exception {
        var admin=user(true);UUID a=email("dead",8),b=job("unknown-sensitive-type",Map.of("to","private-recipient@example.test","urlCiphertext","private cipher"),"dead",11);UUID pending=email("pending",0);email("done",1);
        sql.update("UPDATE outbox_jobs SET created_at='2026-01-01T00:00:00Z',last_error_code='A message containing private content' WHERE id=?",a);sql.update("UPDATE outbox_jobs SET created_at='2026-01-02T00:00:00Z',last_error_code='AppError' WHERE id=?",b);
        var response=send("GET","/api/v1/admin/jobs?limit=1",null,admin,null);assertEquals(200,response.statusCode());var page=tree(response);assertEquals(2,page.path("summary").path("dead").asInt());assertEquals(1,page.path("summary").path("pending").asInt());assertEquals(b.toString(),page.path("items").get(0).path("id").asText());assertEquals("unknown",page.path("items").get(0).path("type").asText());assertEquals("AppError",page.path("items").get(0).path("errorCode").asText());assertEquals(1,page.path("nextOffset").asInt());
        var next=tree(send("GET","/api/v1/admin/jobs?limit=1&offset=1",null,admin,null));assertEquals(a.toString(),next.path("items").get(0).path("id").asText());assertEquals("TASK_FAILED",next.path("items").get(0).path("errorCode").asText());assertTrue(next.path("nextOffset").isNull());assertFalse(response.body().contains("private"));assertFalse(response.body().contains("payload"));assertFalse(response.body().contains("eventKey"));
        assertEquals(pending.toString(),tree(send("GET","/api/v1/admin/jobs?status=pending",null,admin,null)).path("items").get(0).path("id").asText());
    }
    @Test void retryPreservesPayloadEventKeyAndAttemptsWithAtomicAuditAndStableReplay() throws Exception {
        var admin=user(true);UUID id=email("dead",8);sql.update("UPDATE outbox_jobs SET last_error_code='Error',completed_at=now(),leased_until=now() WHERE id=?",id);String payload=sql.queryForObject("SELECT payload::text FROM outbox_jobs WHERE id=?",String.class,id),eventKey=sql.queryForObject("SELECT event_key FROM outbox_jobs WHERE id=?",String.class,id);String key=key();
        var response=send("POST",retryPath(id),retry(8),admin,key);assertEquals(200,response.statusCode());assertEquals(8,tree(response).path("attempts").asInt());assertEquals("pending",state(id));assertEquals(payload,sql.queryForObject("SELECT payload::text FROM outbox_jobs WHERE id=?",String.class,id));assertEquals(eventKey,sql.queryForObject("SELECT event_key FROM outbox_jobs WHERE id=?",String.class,id));assertEquals("Error",sql.queryForObject("SELECT last_error_code FROM outbox_jobs WHERE id=?",String.class,id));assertTrue(sql.queryForObject("SELECT completed_at IS NULL AND leased_until IS NULL FROM outbox_jobs WHERE id=?",Boolean.class,id));
        assertEquals(tree(response),tree(send("POST",retryPath(id),retry(8),admin,key)));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='job_retry_requested' AND resource_id=?",Integer.class,id));assertEquals("Synthetic justified retry",sql.queryForObject("SELECT metadata->>'reason' FROM audit_logs WHERE resource_id=? AND action='job_retry_requested'",String.class,id));
        code(send("POST",retryPath(id),retry(8),admin,key()),409,"JOB_STATE_CONFLICT");code(send("POST",retryPath(id),retry(9),admin,key),409,"IDEMPOTENCY_CONFLICT");assertTrue(processor.processOne());assertEquals("done",state(id));assertEquals(9,attempts(id));assertEquals(tree(response),tree(send("POST",retryPath(id),retry(8),admin,key)));
    }
    @Test void concurrentRetriesOnlyResumeDeadJobOnceAndUnknownTypesStayClosed() throws Exception {
        var admin=user(true);UUID id=email("dead",8);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> send("POST",retryPath(id),retry(8),admin,key()));var b=executor.submit(() -> send("POST",retryPath(id),retry(8),admin,key()));assertEquals(Set.of(200,409),Set.of(a.get(10,TimeUnit.SECONDS).statusCode(),b.get(10,TimeUnit.SECONDS).statusCode()));
        }
        assertEquals(8,attempts(id));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='job_retry_requested' AND resource_id=?",Integer.class,id));UUID unsupported=job("unknown",Map.of(),"dead",8);String key=key();code(send("POST",retryPath(unsupported),retry(8),admin,key),409,"JOB_TYPE_UNSUPPORTED");assertEquals("dead",state(unsupported));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",Integer.class,admin.id(),key));
    }
    @Test void independentJavaInstancesQueueReadsAndRetryFingerprintsReplayInBothDirections() throws Exception {
        var admin=user(true);UUID id=email("dead",8);String raw=id.toString().toUpperCase(Locale.ROOT),key=key();var body=Map.of("expectedAttempts",8.0,"reason"," Synthetic justified retry ");
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            assertEquals(tree(send("GET","/api/v1/admin/jobs?limit=2",null,admin,null)),peer.request("GET","/api/v1/admin/jobs?limit=2",null,headers(admin,null)).path("body"));
            var response=send("POST",retryPath(raw),body,admin,key);assertEquals(200,response.statusCode());assertEquals(raw,tree(response).path("id").asText());var old=peer.request("POST",retryPath(raw),body,headers(admin,key));assertEquals(200,old.path("status").asInt());assertEquals(tree(response),old.path("body"));
            UUID second=email("dead",14);String oldKey=key();var peerFirst=peer.request("POST",retryPath(second),retry(14),headers(admin,oldKey));assertEquals(200,peerFirst.path("status").asInt());assertEquals(peerFirst.path("body"),tree(send("POST",retryPath(second),retry(14),admin,oldKey)));assertEquals(14,attempts(second));
        }
    }
    @Test void rolesLiveRestrictionCsrfStrictValidationAndSwaggerProtectQueueOperations() throws Exception {
        var admin=user(true);var user=user(false);UUID id=email("dead",8);code(send("GET","/api/v1/admin/jobs",null,null,null),401,"UNAUTHENTICATED");code(send("GET","/api/v1/admin/jobs",null,user,null),403,"FORBIDDEN");code(send("POST",retryPath(id),retry(8),user,key()),403,"FORBIDDEN");
        for(String query:List.of("?status=bad","?status=dead&status=pending","?limit=101","?offset=5001","?private=true")) code(send("GET","/api/v1/admin/jobs"+query,null,admin,null),422,"VALIDATION_ERROR");
        for(Object body:List.of(Map.of("expectedAttempts",-1,"reason","valid reason"),Map.of("expectedAttempts",2147483647,"reason","valid reason"),Map.of("expectedAttempts",8.5,"reason","valid reason"),Map.of("expectedAttempts","8","reason","valid reason"),Map.of("expectedAttempts",8,"reason","  "),Map.of("expectedAttempts",8,"reason","valid reason","extra",true))) code(send("POST",retryPath(id),body,admin,key()),422,"VALIDATION_ERROR");
        code(send("POST",retryPath(id),retry(8),admin,null),422,"IDEMPOTENCY_REQUIRED");code(send("POST",retryPath(UUID.randomUUID()),retry(8),admin,key()),404,"JOB_NOT_FOUND");
        var request=HttpRequest.newBuilder(URI.create(server+retryPath(id))).header("Origin",AuthTestSettings.ORIGIN).header("Cookie",admin.cookie()).header("idempotency-key",key()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(retry(8))));code(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),403,"CSRF_INVALID");
        var api=tree(send("GET","/api/docs/json",null,null,null));var list=api.path("paths").path("/api/v1/admin/jobs").path("get");var params=new HashSet<String>();list.path("parameters").forEach(p -> params.add(p.path("name").asText()));assertTrue(params.containsAll(Set.of("status","limit","offset")));var mutation=api.path("paths").path("/api/v1/admin/jobs/{id}/retry").path("post");params.clear();mutation.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) params.add(p.path("name").asText()); });assertTrue(params.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",admin.id());code(send("GET","/api/v1/admin/jobs",null,admin,null),403,"ACCOUNT_RESTRICTED");
    }
    @Test void independentWorkersDeduplicateDeliveryAndPreserveFailureAndLeaseRules() throws Exception {
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            UUID id=email("pending",0);var old=peer.processOne(false);assertEquals(200,old.path("status").asInt());assertTrue(old.path("body").path("processed").asBoolean());assertEquals("done",state(id));assertEquals(1,attempts(id));
            assertFalse(processor.processOne());assertEquals(0,provider.messages.size(),"A committed delivery must not be sent again by another instance");assertEquals("done",state(id));assertEquals(1,attempts(id));assertFalse(peer.processOne(false).path("body").path("processed").asBoolean());
            UUID fresh=email("pending",0);assertTrue(processor.processOne());var delivered=provider.messages.getFirst();assertEquals(fresh,delivered.id());assertEquals("synthetic-recipient@example.test",delivered.to());assertEquals("verify_email",delivered.kind());assertEquals("https://example.test/verify?token=synthetic-local-only",delivered.url());
            UUID failed=email("pending",7);assertTrue(peer.processOne(true).path("body").path("processed").asBoolean());assertEquals("dead",state(failed));assertEquals("Error",sql.queryForObject("SELECT last_error_code FROM outbox_jobs WHERE id=?",String.class,failed));
            UUID localFailed=email("pending",7);provider.mode.set(TrackingEmail.Mode.FAIL);assertTrue(processor.processOne());assertEquals("dead",state(localFailed));assertEquals(8,attempts(localFailed));assertEquals("Error",sql.queryForObject("SELECT last_error_code FROM outbox_jobs WHERE id=?",String.class,localFailed));
            UUID leased=email("processing",1);sql.update("UPDATE outbox_jobs SET leased_until=now()+interval '1 minute' WHERE id=?",leased);assertFalse(peer.processOne(false).path("body").path("processed").asBoolean());assertFalse(processor.processOne());
            sql.update("UPDATE outbox_jobs SET leased_until=now()-interval '1 second' WHERE id=?",leased);assertTrue(peer.processOne(false).path("body").path("processed").asBoolean());assertEquals("done",state(leased));assertEquals(2,attempts(leased));
        }
    }
}
