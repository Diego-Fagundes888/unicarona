package com.unicarona.payment;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.TransferDto;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.provider.*;
import com.unicarona.payment.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.support.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static LedgerService ledger;
    private static TransferService transfers;
    private static TransactionTemplate tx;
    private static TrackingProvider provider;
    private static WebhookAuthentication signing;
    private static String server,passwordHash;
    private static UUID university,course,classId;
    private static final String PASSWORD="Synthetic transfer HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @TestConfiguration(proxyBeanMethods=false) static class ProviderTestConfig {
        @Bean TrackingProvider trackingProvider(BillingSettings settings,WebhookAuthentication authentication) { return new TrackingProvider(new DevelopmentTransferProvider(settings,authentication)); }
    }
    static class TrackingProvider implements TransferProvider {
        enum Mode { NORMAL, LOST_RESPONSE, TIMEOUT }
        final TransferProvider delegate;
        final AtomicReference<Mode> mode=new AtomicReference<>(Mode.NORMAL);
        final AtomicInteger calls=new AtomicInteger();
        final Set<UUID> remoteIds=ConcurrentHashMap.newKeySet();
        final AtomicBoolean interrupted=new AtomicBoolean();
        volatile Input latest;
        TrackingProvider(TransferProvider delegate) { this.delegate=delegate; }
        @Override public Result create(Input input) throws Exception {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());latest=input;calls.incrementAndGet();remoteIds.add(input.transferId());
            if(mode.compareAndSet(Mode.LOST_RESPONSE,Mode.NORMAL)) throw new IOException("Synthetic lost response");
            if(mode.get()==Mode.TIMEOUT) try { new CountDownLatch(1).await(); } catch(InterruptedException error) { interrupted.set(true);throw error; }
            return delegate.create(input);
        }
        @Override public void authenticate(TransferDto.Event event,String timestamp,String signature) { delegate.authenticate(event,timestamp,signature); }
        void reset() { mode.set(Mode.NORMAL);calls.set(0);remoteIds.clear();latest=null;interrupted.set(false); }
    }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.add("--PAYMENT_PROVIDER_TIMEOUT_MS=150");
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,ProviderTestConfig.class).run(args.toArray(String[]::new));
        sql=app.getBean(JdbcTemplate.class);ledger=app.getBean(LedgerService.class);transfers=app.getBean(TransferService.class);provider=app.getBean(TrackingProvider.class);signing=app.getBean(WebhookAuthentication.class);
        assertEquals(1,app.getBeansOfType(TransferProvider.class).size());
        tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('transfer-university','Transfer University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'transfer-course','Transfer Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,1,'A') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void limits() { sql.update("DELETE FROM rate_limits");provider.reset(); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private HttpResponse<String> send(String method,String path,Object body,Session user,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(user!=null) request.header("Cookie",user.cookie()).header("x-csrf-token",user.csrf());if(key!=null) request.header("idempotency-key",key);
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else request.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void code(HttpResponse<String> response,int status,String code) throws Exception { assertEquals(status,response.statusCode());assertEquals(code,tree(response).path("code").asText()); }
    private Session user(boolean admin) throws Exception {
        String email="transfer-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,role,email_verified_at) VALUES(?,?,'Synthetic Transfer','2000-05-01',?,now()) RETURNING id",UUID.class,email,passwordHash,admin?"admin":"user");
        if(!admin) sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',1,'night','Gama')",id,university,course,classId);
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")) {var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private String key() { return "synthetic-transfer-"+UUID.randomUUID(); }
    private void fund(Session user,AccountKind account,int amount) {
        tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer(key(),"synthetic_funding","Fixture only",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-amount),new Posting(user.id(),account,amount)))));
    }
    private UUID request(Session user,String type,int amount,String key) throws Exception {
        var response=send("POST","/api/v1/transfers",new TransferDto.Request(type,amount),user,key);assertEquals(200,response.statusCode());assertEquals("pending",tree(response).path("status").asText());return UUID.fromString(tree(response).path("id").asText());
    }
    private TransferDto.Event event(UUID id,int amount,String status,String eventId) { return new TransferDto.Event(eventId,id.toString(),"dev_transfer_"+id,amount,status); }
    private HttpResponse<String> receive(TransferDto.Event event) throws Exception {
        String timestamp=Long.toString(java.time.Instant.now().getEpochSecond());return receive(event,timestamp,signing.signature("transfer-v1",timestamp,event));
    }
    private HttpResponse<String> receive(TransferDto.Event event,String timestamp,String signature) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/webhooks/transfers")).header("Content-Type","application/json");
        if(timestamp!=null) request.header("x-webhook-timestamp",timestamp);if(signature!=null) request.header("x-webhook-signature",signature);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void requestReservesOnlyAvailableMoneyWithCadenceOutboxAuditAndStableReplay() throws Exception {
        var owner=user(false);fund(owner,AccountKind.DRIVER_AVAILABLE,1800);sql.update("UPDATE student_profiles SET withdrawal_cadence='monthly' WHERE user_id=?",owner.id());
        String key=key();UUID id=request(owner,"withdraw",1000,key);assertEquals(800,ledger.balance(owner.id(),AccountKind.DRIVER_AVAILABLE));assertEquals(1000,ledger.balance(owner.id(),AccountKind.PAYOUT_PENDING));
        assertEquals("monthly",sql.queryForObject("SELECT cadence FROM transfers WHERE id=?",String.class,id));assertEquals(0,provider.calls.get());
        assertEquals(id,request(owner,"withdraw",1000,key));assertEquals(1,sql.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,owner.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE event_key=? AND status='pending'",Integer.class,"transfer:"+id));
        assertEquals(id.toString(),sql.queryForObject("SELECT payload->>'transferId' FROM outbox_jobs WHERE event_key=?",String.class,"transfer:"+id));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='transfer_requested' AND resource_id=?",Integer.class,id));
        code(send("POST","/api/v1/transfers",new TransferDto.Request("withdraw",900),owner,key),409,"IDEMPOTENCY_CONFLICT");
        var list=tree(send("GET","/api/v1/transfers",null,owner,null));assertEquals(id.toString(),list.path("items").get(0).path("id").asText());assertTrue(list.path("items").get(0).path("createdAt").asText().matches(".*\\.\\d{3}Z"));
    }
    @Test void unavailableFutureBalanceRollsBackTransferKeyAndJobAndRestrictedAccountsCanRefund() throws Exception {
        var owner=user(false);fund(owner,AccountKind.FUTURE,1000);String key=key();
        code(send("POST","/api/v1/transfers",new TransferDto.Request("withdraw",600),owner,key),409,"INSUFFICIENT_BALANCE");
        code(send("POST","/api/v1/transfers",new TransferDto.Request("refund",600),owner,key),409,"INSUFFICIENT_BALANCE");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM transfers WHERE user_id=?",Integer.class,owner.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=?",Integer.class,owner.id()));
        fund(owner,AccountKind.PASSENGER_CREDIT,900);sql.update("UPDATE users SET account_status='suspended' WHERE id=?",owner.id());UUID id=request(owner,"refund",600,key);
        assertNull(sql.queryForObject("SELECT cadence FROM transfers WHERE id=?",String.class,id));assertEquals(200,send("GET","/api/v1/transfers",null,owner,null).statusCode());
        var admin=user(true);code(send("POST","/api/v1/transfers",new TransferDto.Request("refund",100),admin,key()),422,"PROFILE_REQUIRED");
    }
    @Test void signedApprovedAndFailedOutcomesMoveFundsExactlyOnceForWithdrawAndRefund() throws Exception {
        for(String type:List.of("withdraw","refund")) for(String outcome:List.of("approved","failed")) {
            var owner=user(false);var source=type.equals("withdraw")?AccountKind.DRIVER_AVAILABLE:AccountKind.PASSENGER_CREDIT;fund(owner,source,1500);
            sql.update("UPDATE student_profiles SET notices=false WHERE user_id=?",owner.id());UUID id=request(owner,type,1000,key());transfers.submit(id);transfers.submit(id);
            var payload=event(id,1000,outcome,"evt_"+UUID.randomUUID());var result=receive(payload);assertEquals(200,result.statusCode());assertEquals(json.readTree("{\"received\":true,\"duplicate\":false}"),tree(result));
            assertEquals("pending",sql.queryForObject("SELECT status FROM outbox_jobs WHERE event_key=?",String.class,"transfer:"+id));
            assertEquals(outcome,sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,id));
            assertEquals(0,ledger.balance(owner.id(),type.equals("withdraw")?AccountKind.PAYOUT_PENDING:AccountKind.REFUND_PENDING));
            assertEquals(outcome.equals("failed")?1500:500,ledger.balance(owner.id(),source));
            assertEquals(outcome.equals("approved")?1000:0,ledger.balance(owner.id(),type.equals("withdraw")?AccountKind.PAID_OUT:AccountKind.REFUNDED));
            assertTrue(tree(receive(payload)).path("duplicate").asBoolean());assertTrue(tree(receive(event(id,1000,outcome,"evt_"+UUID.randomUUID()))).path("duplicate").asBoolean());
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE ref_id=?",Integer.class,id));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='transfer_provider_result' AND resource_id=?",Integer.class,id));
        }
    }
    @Test void invalidSignaturesNeverPersistAndFailedReceiptsRemainRetryableWithoutRestoringBalanceEarly() throws Exception {
        var owner=user(false);fund(owner,AccountKind.PASSENGER_CREDIT,1000);UUID id=request(owner,"refund",700,key());var payload=event(id,700,"approved","evt_"+UUID.randomUUID());String now=Long.toString(java.time.Instant.now().getEpochSecond());
        code(receive(payload,now,"00".repeat(32)),401,"WEBHOOK_UNAUTHORIZED");code(receive(payload,null,null),401,"WEBHOOK_UNAUTHORIZED");
        String old=Long.toString(java.time.Instant.now().getEpochSecond()-301);code(receive(payload,old,signing.signature("transfer-v1",old,payload)),401,"WEBHOOK_UNAUTHORIZED");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id=?",Integer.class,payload.eventId()));
        code(receive(payload),422,"WEBHOOK_MISMATCH");assertEquals("failed",sql.queryForObject("SELECT status FROM webhook_events WHERE event_id=?",String.class,payload.eventId()));
        assertEquals("WEBHOOK_MISMATCH",sql.queryForObject("SELECT last_error_code FROM webhook_events WHERE event_id=?",String.class,payload.eventId()));assertEquals(700,ledger.balance(owner.id(),AccountKind.REFUND_PENDING));
        transfers.submit(id);assertEquals(200,receive(payload).statusCode());assertEquals("processed",sql.queryForObject("SELECT status FROM webhook_events WHERE event_id=?",String.class,payload.eventId()));
        assertNull(sql.queryForObject("SELECT last_error_code FROM webhook_events WHERE event_id=?",String.class,payload.eventId()));
        var missing=event(UUID.randomUUID(),700,"approved","evt_"+UUID.randomUUID());code(receive(missing),404,"TRANSFER_NOT_FOUND");
        assertEquals("failed",sql.queryForObject("SELECT status FROM webhook_events WHERE event_id=?",String.class,missing.eventId()));
    }
    @Test void changedPayloadAndContradictoryProviderResultsKeepMoneyAndTheOriginalOutcome() throws Exception {
        var owner=user(false);fund(owner,AccountKind.DRIVER_AVAILABLE,1500);UUID id=request(owner,"withdraw",1000,key());transfers.submit(id);
        var peer=event(id,1000,"approved","evt_"+UUID.randomUUID());assertEquals(200,receive(peer).statusCode());
        code(receive(event(id,999,"approved",peer.eventId())),409,"WEBHOOK_CONFLICT");
        var contradictory=event(id,1000,"failed","evt_"+UUID.randomUUID());code(receive(contradictory),409,"TRANSFER_STATE_CONFLICT");
        assertEquals("TRANSFER_STATE_CONFLICT",sql.queryForObject("SELECT last_error_code FROM webhook_events WHERE event_id=?",String.class,contradictory.eventId()));assertEquals(1000,ledger.balance(owner.id(),AccountKind.PAID_OUT));assertEquals(500,ledger.balance(owner.id(),AccountKind.DRIVER_AVAILABLE));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"transfer:"+id+":completed"));
    }
    @Test void concurrentRequestsAndReceiptsCannotSpendOrSettleTwice() throws Exception {
        var owner=user(false);fund(owner,AccountKind.PASSENGER_CREDIT,1000);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> send("POST","/api/v1/transfers",new TransferDto.Request("refund",700),owner,key()));
            var b=executor.submit(() -> send("POST","/api/v1/transfers",new TransferDto.Request("refund",700),owner,key()));
            var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);assertEquals(Set.of(200,409),Set.of(first.statusCode(),second.statusCode()));
            UUID id=UUID.fromString(tree(first.statusCode()==200?first:second).path("id").asText());transfers.submit(id);
            var event=event(id,700,"approved","evt_"+UUID.randomUUID());var c=executor.submit(() -> receive(event));var d=executor.submit(() -> receive(event));
            var third=c.get(15,TimeUnit.SECONDS);var fourth=d.get(15,TimeUnit.SECONDS);assertEquals(200,third.statusCode());assertEquals(200,fourth.statusCode());assertEquals(Set.of(true,false),Set.of(tree(third).path("duplicate").asBoolean(),tree(fourth).path("duplicate").asBoolean()));
            assertEquals(700,ledger.balance(owner.id(),AccountKind.REFUNDED));assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE ref_id=?",Integer.class,id));
        }
    }
    @Test void providerRetryReusesTheRemoteIdAndTimeoutRetainsReservedFundsWithoutHoldingLocks() throws Exception {
        var owner=user(false);fund(owner,AccountKind.PASSENGER_CREDIT,1500);UUID id=request(owner,"refund",800,key());
        provider.mode.set(TrackingProvider.Mode.LOST_RESPONSE);assertEquals("TRANSFER_PROVIDER_UNAVAILABLE",assertThrows(ApiException.class,() -> transfers.submit(id)).code());
        assertNull(sql.queryForObject("SELECT provider_reference FROM transfers WHERE id=?",String.class,id));assertEquals(800,ledger.balance(owner.id(),AccountKind.REFUND_PENDING));
        transfers.submit(id);assertEquals(2,provider.calls.get());assertEquals(Set.of(id),provider.remoteIds);assertEquals(id,provider.latest.transferId());assertEquals(800,provider.latest.amountCents());
        UUID timed=request(owner,"refund",400,key());provider.mode.set(TrackingProvider.Mode.TIMEOUT);
        assertEquals("TRANSFER_PROVIDER_UNAVAILABLE",assertThrows(ApiException.class,() -> transfers.submit(timed)).code());
        assertTrue(provider.interrupted.get() || awaitInterrupted());
        tx.executeWithoutResult(status -> {sql.execute("SET LOCAL lock_timeout='100ms'");sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner.id());sql.queryForList("SELECT id FROM transfers WHERE id=? FOR UPDATE",timed);});
        assertEquals("pending",sql.queryForObject("SELECT status FROM transfers WHERE id=?",String.class,timed));assertEquals(1200,ledger.balance(owner.id(),AccountKind.REFUND_PENDING));
    }
    private boolean awaitInterrupted() throws InterruptedException { for(int i=0;i<20 && !provider.interrupted.get();i++) Thread.sleep(10);return provider.interrupted.get(); }
    @Test void originalRequestsSubmissionsAndWebhooksRemainCompatibleInBothDirections() throws Exception {
        var owner=user(false);fund(owner,AccountKind.PASSENGER_CREDIT,1800);String key=key();var input=new TransferDto.Request("refund",500);
        var headers=Map.of("origin",AuthTestSettings.ORIGIN,"cookie",owner.cookie(),"x-csrf-token",owner.csrf(),"idempotency-key",key);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var old=peer.request("POST","/api/v1/transfers",input,headers);assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),tree(send("POST","/api/v1/transfers",input,owner,key)));
            UUID id=UUID.fromString(old.path("body").path("id").asText());assertEquals(200,peer.submitTransfer(id).path("status").asInt());
            var event=event(id,500,"approved","evt_"+UUID.randomUUID());assertEquals(200,receive(event).statusCode());String timestamp=Long.toString(java.time.Instant.now().getEpochSecond());
            var oldEvent=peer.request("POST","/api/v1/webhooks/transfers",event,Map.of("x-webhook-timestamp",timestamp,"x-webhook-signature",signing.signature("transfer-v1",timestamp,event)));assertEquals(200,oldEvent.path("status").asInt());assertTrue(oldEvent.path("body").path("duplicate").asBoolean());
            String nextKey=key();UUID next=request(owner,"refund",400,nextKey);transfers.submit(next);var nextEvent=event(next,400,"failed","evt_"+UUID.randomUUID());
            var nextOld=peer.request("POST","/api/v1/webhooks/transfers",nextEvent,Map.of("x-webhook-timestamp",timestamp,"x-webhook-signature",signing.signature("transfer-v1",timestamp,nextEvent)));assertEquals(200,nextOld.path("status").asInt());assertFalse(nextOld.path("body").path("duplicate").asBoolean());assertTrue(tree(receive(nextEvent)).path("duplicate").asBoolean());
            var list=peer.request("GET","/api/v1/transfers?limit=1&offset=1",null,Map.of("cookie",owner.cookie()));assertEquals(list.path("body"),tree(send("GET","/api/v1/transfers?limit=1&offset=1",null,owner,null)));
        }
    }
    @Test void requestValidationHistoryPrivacyAndWebhookSwaggerAreComplete() throws Exception {
        var owner=user(false);var other=user(false);fund(owner,AccountKind.PASSENGER_CREDIT,900);for(int i=0;i<3;i++) request(owner,"refund",100,key());
        assertEquals(0,tree(send("GET","/api/v1/transfers",null,other,null)).path("items").size());var page=tree(send("GET","/api/v1/transfers?limit=1&offset=1",null,owner,null));assertEquals(2,page.path("nextOffset").asInt());
        for(String query:List.of("limit=0","offset=5001","limit=1&limit=2","unknown=x")) code(send("GET","/api/v1/transfers?"+query,null,owner,null),422,"VALIDATION_ERROR");
        for(Object body:List.of(Map.of("type","refund","cents",0),Map.of("type","refund","cents","100"),Map.of("type","refund","cents",1.5),Map.of("type","refund","cents",100000001),Map.of("type","invalid","cents",100),Map.of("type","refund","cents",100,"extra",true))) code(send("POST","/api/v1/transfers",body,owner,key()),422,"VALIDATION_ERROR");
        code(send("POST","/api/v1/transfers",new TransferDto.Request("refund",100),owner,null),422,"IDEMPOTENCY_REQUIRED");
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/transfers")).header("Origin",AuthTestSettings.ORIGIN).header("Cookie",owner.cookie()).header("idempotency-key",key()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"refund\",\"cents\":100}"));
        code(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),403,"CSRF_INVALID");
        var paths=tree(send("GET","/api/docs/json",null,null,null)).path("paths");var hook=paths.path("/api/v1/webhooks/transfers").path("post");assertTrue(hook.path("security").isMissingNode() || hook.path("security").size()==0);
        var parameters=new HashSet<String>();hook.path("parameters").forEach(p -> {assertTrue(p.path("required").asBoolean());parameters.add(p.path("name").asText());});assertEquals(Set.of("x-webhook-timestamp","x-webhook-signature"),parameters);
    }
}
