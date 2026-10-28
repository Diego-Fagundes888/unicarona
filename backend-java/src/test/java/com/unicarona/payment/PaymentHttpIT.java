package com.unicarona.payment;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.provider.*;
import com.unicarona.payment.service.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import java.io.IOException;
import java.util.concurrent.atomic.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PaymentHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static LedgerService ledger;
    private static TransactionTemplate tx;
    private static TrackingProvider provider;
    private static WebhookAuthentication signing;
    private static UUID university,course,classId,otherClass;
    private static String server,passwordHash,start;
    private static final String PASSWORD="Synthetic payment HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.add("--PAYMENT_PROVIDER_TIMEOUT_MS=500");
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,ProviderTestConfig.class).run(args.toArray(String[]::new));
        ledger=app.getBean(LedgerService.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));provider=app.getBean(TrackingProvider.class);signing=app.getBean(WebhookAuthentication.class);assertEquals(1,app.getBeansOfType(PaymentProvider.class).size());
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1).withDayOfMonth(1).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('payment-university','Routine University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'payment-course','Routine Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void resetSettings() { sql.update("DELETE FROM rate_limits");provider.reset();sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private Session user(boolean driver,UUID selectedClass) throws Exception {
        String email="routine-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic Routine','2000-05-01',now()) RETURNING id",UUID.class,email,passwordHash);
        com.unicarona.support.LegalTestFixtures.acceptedBusinessUser(app,id);
        sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',3,'night','Gama')",id,university,course,selectedClass);
        if(driver) { sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",id);sql.update("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic Car','Black','ABC1D23',4,'approved')",id); }
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")) { var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]); }
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private Session user(boolean driver) throws Exception { return user(driver,classId); }
    private HttpResponse<String> send(String method,String path,Object body,Session user,String key) throws Exception {
        return sendRaw(method,path,CheckoutTestFixtures.reviewedBody(app,method,path,body),user,key);
    }
    private HttpResponse<String> sendRaw(String method,String path,Object body,Session user,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(user!=null) request.header("Cookie",user.cookie()).header("x-csrf-token",user.csrf());if(key!=null) request.header("idempotency-key",key);
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else request.method(method,HttpRequest.BodyPublishers.noBody());return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void code(HttpResponse<String> response,int status,String code) throws Exception { assertEquals(status,response.statusCode());assertEquals(code,tree(response).path("code").asText()); }
    private String key() { return "synthetic-routine-"+UUID.randomUUID(); }
    private Map<String,String> headers(Session user,String key) { var result=new HashMap<String,String>();result.put("cookie",user.cookie());result.put("origin",AuthTestSettings.ORIGIN);result.put("x-csrf-token",user.csrf());if(key!=null) result.put("idempotency-key",key);return result; }
    private Map<String,Object> routine() {
        var input=new LinkedHashMap<String,Object>();input.put("region"," Gáma ");input.put("meeting"," Synthetic meeting ");input.put("direction","ambos");input.put("time","18:00");input.put("returnTime","21:00");input.put("weekdays",List.of(3,1,5));input.put("capacity",4);input.put("distance",10.0);input.put("automatic",true);return input;
    }
    private UUID publish(Session driver,Map<String,Object> input) throws Exception { var response=send("POST","/api/v1/routines",input,driver,key());assertEquals(200,response.statusCode());return UUID.fromString(tree(response).path("id").asText()); }
    private Map<String,Object> monthly(UUID id) { return new LinkedHashMap<>(Map.of("routineId",id.toString(),"startDate",start,"directions","ambos","weekdays",List.of(5,1,3))); }
    private JsonNode preview(Session passenger,UUID routine) throws Exception { var response=send("POST","/api/v1/subscriptions/preview",monthly(routine),passenger,null);assertEquals(200,response.statusCode());return tree(response); }
    private UUID subscription(Session driver,Session passenger,UUID routine,String end) {
        return sql.queryForObject("INSERT INTO subscriptions(routine_id,driver_id,passenger_id,class_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status) VALUES(?,?,?,?,current_date,?::date,ARRAY[1,3,5]::smallint[],'ambos',400,1000,'active') RETURNING id",UUID.class,routine,driver.id(),passenger.id(),classId,end);
    }
    private static final String REQUESTS="/api/v1/ride-requests";
    private UUID ride(Session driver,boolean automatic,int capacity) throws Exception {
        var input=new LinkedHashMap<String,Object>();input.put("region","Gama");input.put("meeting","Synthetic stop");input.put("direction","ida");input.put("date",start);input.put("time","18:30");input.put("capacity",capacity);input.put("distance",10);input.put("automatic",automatic);
        var response=send("POST","/api/v1/rides",input,driver,key());assertEquals(200,response.statusCode());return UUID.fromString(tree(response).path("id").asText());
    }
    private Map<String,Object> avulsa(UUID ride) { return Map.of("type","avulsa","rideId",ride.toString()); }
    private Map<String,Object> mensal(UUID routine) { var input=monthly(routine);input.put("type","mensal");return input; }
    private UUID request(Session passenger,Map<String,Object> input) throws Exception {
        var response=send("POST",REQUESTS,input,passenger,key());assertEquals(200,response.statusCode(),response.body());return UUID.fromString(tree(response).path("id").asText());
    }
    @TestConfiguration(proxyBeanMethods=false) static class ProviderTestConfig {
        @Bean TrackingProvider trackingProvider(BillingSettings settings,AuthSettings auth,WebhookAuthentication authentication) { return new TrackingProvider(new DevelopmentPaymentProvider(settings,auth,authentication)); }
    }
    static class TrackingProvider implements PaymentProvider {
        enum Mode { NORMAL, LOST_RESPONSE, TIMEOUT, BLOCK }
        final PaymentProvider delegate;
        final AtomicReference<Mode> mode=new AtomicReference<>(Mode.NORMAL);
        final AtomicInteger calls=new AtomicInteger();
        final Set<UUID> remoteIds=ConcurrentHashMap.newKeySet();
        final AtomicBoolean interrupted=new AtomicBoolean();
        volatile CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        TrackingProvider(PaymentProvider delegate) { this.delegate=delegate; }
        @Override public String name() { return delegate.name(); }
        @Override public Checkout createCheckout(Input input) throws Exception {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();remoteIds.add(input.paymentId());started.countDown();
            if(mode.compareAndSet(Mode.LOST_RESPONSE,Mode.NORMAL)) throw new IOException("Synthetic lost response");
            if(mode.get()==Mode.TIMEOUT) try { new CountDownLatch(1).await(); } catch(InterruptedException error) { interrupted.set(true);throw error; }
            if(mode.get()==Mode.BLOCK) release.await();return delegate.createCheckout(input);
        }
        @Override public void authenticate(PaymentDto.Event event,String timestamp,String signature) { delegate.authenticate(event,timestamp,signature); }
        void reset() { mode.set(Mode.NORMAL);calls.set(0);remoteIds.clear();interrupted.set(false);started=new CountDownLatch(1);release=new CountDownLatch(1); }
    }
    private UUID payment(Session passenger,UUID ride) throws Exception {
        UUID request=request(passenger,avulsa(ride));return sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,request);
    }
    private UUID monthlyPayment(Session driver,Session passenger,UUID routine,Map<String,Object> input) throws Exception {
        UUID request=request(passenger,input);var response=send("POST",REQUESTS+"/"+request+"/respond",Map.of("accept",true),driver,key());assertEquals(200,response.statusCode());return UUID.fromString(tree(response).path("paymentId").asText());
    }
    private String checkoutPath(UUID id) { return "/api/v1/payments/"+id+"/checkout"; }
    private Map<String,Object> checkoutInput(boolean credit) { return Map.of("method","pix","useCredit",credit); }
    private JsonNode checkout(Session passenger,UUID payment,boolean credit) throws Exception {
        var response=send("POST",checkoutPath(payment),checkoutInput(credit),passenger,key());assertEquals(200,response.statusCode(),response.body());return tree(response);
    }
    private PaymentDto.Event event(UUID payment,String status) {
        return new PaymentDto.Event("synthetic-event-"+UUID.randomUUID(),payment.toString(),sql.queryForObject("SELECT provider_reference FROM payments WHERE id=?",String.class,payment),sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,payment),status);
    }
    private Map<String,String> signedHeaders(PaymentDto.Event event) {
        String timestamp=Long.toString(Instant.now().getEpochSecond());return Map.of("x-webhook-timestamp",timestamp,"x-webhook-signature",signing.signature("",timestamp,event));
    }
    private HttpResponse<String> webhook(PaymentDto.Event event) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/webhooks/payments")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event)));signedHeaders(event).forEach(request::header);return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void credit(Session passenger,long cents) {
        tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer("synthetic-funding-"+UUID.randomUUID(),"synthetic_funding","Synthetic payment fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-cents),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,cents)))));
    }
    private void confirmed(UUID payment,int legs) {
        assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));assertEquals("confirmed",sql.queryForObject("SELECT q.status FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE p.id=?",String.class,payment));
        assertEquals(legs,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",Integer.class,payment));assertEquals(legs,sql.queryForObject("SELECT count(*) FROM parcels WHERE payment_id=? AND status='future' AND commission_cents=0 AND driver_cents=0",Integer.class,payment));
        assertTrue(sql.queryForObject("SELECT p.amount_cents=(SELECT sum(cents) FROM parcels WHERE payment_id=p.id) FROM payments p WHERE id=?",Boolean.class,payment));assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",Integer.class,payment));assertEquals(0,ledger.reconciliation().difference());
    }
    private JsonNode tripSummary(Session passenger,UUID payment)throws Exception {
        var result=sendRaw("GET","/api/v1/payments/"+payment+"/summary",null,passenger,null);assertEquals(200,result.statusCode(),result.body());return tree(result);
    }
    private Map<String,Object> reviewed(JsonNode summary){return Map.of("method","pix","useCredit",false,"tripReview",summary.path("tripReview").asText());}
    @Test void generatedPaymentSummarySchemaCannotCollideWithAnotherSummary()throws Exception {
        var response=sendRaw("GET","/api/docs/json",null,null,null);assertEquals(200,response.statusCode());var schema=tree(response);var types=schema.path("components").path("schemas");
        assertEquals("#/components/schemas/PaymentSummary",schema.path("paths").path("/api/v1/payments/{id}/summary").path("get").path("responses").path("200").path("content").path("*/*").path("schema").path("$ref").asText());
        assertEquals("#/components/schemas/PaymentTrip",types.path("PaymentSummary").path("properties").path("trips").path("items").path("$ref").asText());
        assertEquals("integer",types.path("PaymentTrip").path("properties").path("cents").path("type").asText());assertTrue(types.path("PaymentSummary").path("properties").has("tripReview"));
        assertTrue(types.path("PaymentCheckoutInput").path("properties").has("tripReview"));
    }
    @Test void firstCheckoutRequiresTheActuallyCurrentTripReviewAndRetryDoesNotCreateAnotherCharge()throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,true,2),payment=payment(passenger,ride);
        var before=tripSummary(passenger,payment);assertEquals(1,before.path("trips").size());var trip=before.path("trips").get(0);
        assertEquals("Synthetic stop",trip.path("meeting").asText());assertEquals("Gama",trip.path("origin").asText());assertEquals(start,trip.path("date").asText());assertTrue(trip.path("detailsAvailable").asBoolean());assertEquals(before.path("totalCents").asInt(),trip.path("cents").asInt());assertTrue(before.path("tripReview").asText().matches("[a-f0-9]{64}"));
        String key=key();code(sendRaw("POST",checkoutPath(payment),checkoutInput(false),passenger,key),409,"TRIP_REVIEW_REQUIRED");assertEquals(0,provider.calls.get());
        code(sendRaw("POST",checkoutPath(payment),Map.of("method","pix","useCredit",false,"tripReview","invalid"),passenger,key),422,"VALIDATION_ERROR");
        var explicitNull=new LinkedHashMap<String,Object>(checkoutInput(false));explicitNull.put("tripReview",null);code(sendRaw("POST",checkoutPath(payment),explicitNull,passenger,key),422,"VALIDATION_ERROR");
        var edited=send("PATCH","/api/v1/rides/"+ride,Map.of("capacity",2,"distance",10,"automatic",true,"notes","Synthetic updated meeting instructions"),driver,key());assertEquals(200,edited.statusCode(),edited.body());
        var current=tripSummary(passenger,payment);assertNotEquals(before.path("tripReview"),current.path("tripReview"));assertEquals("Synthetic updated meeting instructions",current.path("trips").get(0).path("notes").asText());
        code(sendRaw("POST",checkoutPath(payment),reviewed(before),passenger,key),409,"TRIP_REVIEW_REQUIRED");assertEquals("awaiting",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));assertEquals(0,provider.calls.get());assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",Integer.class,payment));
        var accepted=sendRaw("POST",checkoutPath(payment),reviewed(current),passenger,key);assertEquals(200,accepted.statusCode(),accepted.body());assertEquals(1,provider.calls.get());
        assertEquals(accepted.body(),sendRaw("POST",checkoutPath(payment),reviewed(before),passenger,key).body());
        assertEquals(200,sendRaw("POST",checkoutPath(payment),checkoutInput(false),passenger,key()).statusCode());assertEquals(1,provider.calls.get());
    }
    @Test void tripReviewIsOwnedAndExactDetailsDisappearOnExpiryBlockingOrEndedPaymentPurpose()throws Exception {
        var driver=user(true);var passenger=user(false);var other=user(false);UUID ride=ride(driver,true,2),payment=payment(passenger,ride);
        code(sendRaw("GET","/api/v1/payments/"+payment+"/summary",null,other,null),404,"PAYMENT_NOT_FOUND");
        for(String state:List.of("payment-expired","request-expired","blocked","driver-deleted","ride-cancelled")){
            tx.executeWithoutResult(ignored->{
                switch(state){
                    case "payment-expired"->sql.update("UPDATE payments SET expires_at=now()-interval '1 minute' WHERE id=?",payment);
                    case "request-expired"->sql.update("UPDATE ride_requests SET expires_at=now()-interval '1 minute' WHERE id=(SELECT request_id FROM payments WHERE id=?)",payment);
                    case "blocked"->sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());
                    case "driver-deleted"->sql.update("UPDATE users SET deleted_at=now(),account_status='deleted' WHERE id=?",driver.id());
                    case "ride-cancelled"->sql.update("UPDATE rides SET status='cancelled' WHERE id=?",ride);
                }
            });
            var hidden=tripSummary(passenger,payment);assertTrue(hidden.path("tripReview").isNull(),state);assertFalse(hidden.path("trips").get(0).path("detailsAvailable").asBoolean(),state);assertTrue(hidden.path("trips").get(0).path("meeting").isNull(),state);assertTrue(hidden.path("trips").get(0).path("notes").isNull(),state);assertFalse(hidden.toString().contains("Synthetic stop"),state);
            sql.update("UPDATE payments SET expires_at=now()+interval '1 hour' WHERE id=?",payment);sql.update("UPDATE ride_requests SET expires_at=now()+interval '1 hour' WHERE id=(SELECT request_id FROM payments WHERE id=?)",payment);sql.update("DELETE FROM user_blocks WHERE by_id=? AND other_id=?",driver.id(),passenger.id());sql.update("UPDATE users SET deleted_at=NULL,account_status='active' WHERE id=?",driver.id());
        }
        UUID paidPayment=payment(passenger,ride(driver,true,2));checkout(passenger,paidPayment,false);assertEquals(200,webhook(event(paidPayment,"approved")).statusCode());var paid=tripSummary(passenger,paidPayment);assertTrue(paid.path("tripReview").isNull());assertTrue(paid.path("trips").get(0).path("meeting").isNull());assertTrue(paid.path("trips").get(0).path("notes").isNull());
        var allowed=tripSummary(passenger,payment);var fields=new HashSet<String>();allowed.path("trips").get(0).fieldNames().forEachRemaining(fields::add);assertEquals(Set.of("rideId","date","time","direction","origin","destination","meeting","notes","cents","version","detailsAvailable"),fields);
    }
    @Test void monthlyReviewCoversAllChronologicalLegsAndChangingTheLastLegRejectsTheOldReview()throws Exception {
        var driver=user(true);var passenger=user(false);var input=routine();input.put("notes","Synthetic monthly instructions");UUID routine=publish(driver,input),payment=monthlyPayment(driver,passenger,routine,mensal(routine));
        var before=tripSummary(passenger,payment);var trips=before.path("trips");assertTrue(trips.size()>2);assertEquals(before.path("rideCount").asInt(),trips.size());String previous="";
        long sum=0;for(var trip:trips){String order=trip.path("date").asText()+trip.path("time").asText();assertTrue(order.compareTo(previous)>=0);previous=order;assertTrue(trip.path("detailsAvailable").asBoolean());assertEquals("Synthetic monthly instructions",trip.path("notes").asText());assertEquals(sql.queryForObject("SELECT cents FROM request_lines WHERE request_id=(SELECT request_id FROM payments WHERE id=?) AND ride_id=?",Integer.class,payment,UUID.fromString(trip.path("rideId").asText())).intValue(),trip.path("cents").asInt());sum+=trip.path("cents").asLong();}assertEquals(before.path("totalCents").asLong(),sum);
        UUID first=UUID.fromString(trips.get(0).path("rideId").asText()),second=UUID.fromString(trips.get(1).path("rideId").asText());sql.update("UPDATE rides SET price_cents=price_cents+123 WHERE id=?",first);assertEquals(before.path("trips").get(0).path("cents"),tripSummary(passenger,payment).path("trips").get(0).path("cents"),"Current offer price must not replace the agreed request-line price");
        sql.update("UPDATE request_lines SET cents=cents+CASE WHEN ride_id=? THEN 1 ELSE -1 END WHERE request_id=(SELECT request_id FROM payments WHERE id=?) AND ride_id IN (?,?)",first,payment,first,second);var repriced=tripSummary(passenger,payment);assertNotEquals(before.path("tripReview"),repriced.path("tripReview"));code(sendRaw("POST",checkoutPath(payment),reviewed(before),passenger,key()),409,"TRIP_REVIEW_REQUIRED");assertEquals(0,provider.calls.get());
        UUID last=UUID.fromString(trips.get(trips.size()-1).path("rideId").asText());sql.update("UPDATE rides SET notes='Synthetic last-leg change',version=version+1 WHERE id=?",last);
        code(sendRaw("POST",checkoutPath(payment),reviewed(before),passenger,key()),409,"TRIP_REVIEW_REQUIRED");assertEquals(0,provider.calls.get());var current=tripSummary(passenger,payment);assertNotEquals(before.path("tripReview"),current.path("tripReview"));assertEquals(200,sendRaw("POST",checkoutPath(payment),reviewed(current),passenger,key()).statusCode());assertEquals(1,provider.calls.get());
    }
    @Test void checkoutRechecksAfterWaitingForTheRideLockBeforeAnyProviderCall()throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,true,2),payment=payment(passenger,ride);var before=tripSummary(passenger,payment);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var waiting=new AtomicReference<Future<HttpResponse<String>>>();
            tx.executeWithoutResult(ignored->{
                sql.queryForList("SELECT id FROM rides WHERE id=? FOR UPDATE",ride);
                waiting.set(pool.submit(()->sendRaw("POST",checkoutPath(payment),reviewed(before),passenger,key())));
                boolean locked=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<until){sql.execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FROM request_lines l%' AND query LIKE '%FOR UPDATE OF r%')",Boolean.class))){locked=true;break;}try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
                assertTrue(locked,"Checkout must reach the actual ride lock");sql.update("UPDATE rides SET notes='Synthetic concurrent change',version=version+1 WHERE id=?",ride);
            });
            code(waiting.get().get(15,TimeUnit.SECONDS),409,"TRIP_REVIEW_REQUIRED");assertEquals(0,provider.calls.get());assertEquals("awaiting",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));
        }
    }
    @Test void checkoutPreparesExternalChargeWithoutOccupyingSeatAndSignedReceiptConfirmsOnce() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,true,1),payment=payment(passenger,ride);String key=key();var response=send("POST",checkoutPath(payment),checkoutInput(false),passenger,key);assertEquals(200,response.statusCode());var body=tree(response);
        assertEquals("pending",body.path("status").asText());assertFalse(body.path("confirmed").asBoolean());assertEquals("test",body.path("checkout").path("environment").asText());assertEquals("dev_"+payment,body.path("checkout").path("reference").asText());assertEquals(1,provider.calls.get());assertEquals(body,tree(send("POST",checkoutPath(payment),checkoutInput(false),passenger,key)));assertEquals(1,provider.calls.get());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));var event=event(payment,"approved");response=webhook(event);assertEquals(200,response.statusCode(),response.body());assertFalse(tree(response).path("duplicate").asBoolean());confirmed(payment,1);
        assertTrue(tree(webhook(event)).path("duplicate").asBoolean());assertTrue(tree(webhook(event(payment,"failed"))).path("duplicate").asBoolean());confirmed(payment,1);
        var row=sql.queryForMap("SELECT pin_ciphertext,pin_hash FROM ride_reservations WHERE payment_id=?",payment);var crypto=app.getBean(CryptoService.class);String pin=crypto.decrypt((String)row.get("pin_ciphertext"),AuthTestSettings.KEY);assertTrue(pin.matches("[0-9]{4}"));assertEquals(crypto.hmac(pin,AuthTestSettings.PEPPER),row.get("pin_hash"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='payment_event_processed' AND resource_id=?",Integer.class,payment));
    }
    @Test void fullCreditConfirmsWithoutGatewayAndPartialCreditIsConsumedOnlyAfterReceipt() throws Exception {
        var driver=user(true);var passenger=user(false);UUID first=payment(passenger,ride(driver,true,2));int amount=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,first);credit(passenger,amount+50);var result=checkout(passenger,first,true);assertTrue(result.path("confirmed").asBoolean());assertTrue(result.path("checkout").isNull());assertEquals(0,provider.calls.get());confirmed(first,1);assertEquals(50,ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));
        var nextDriver=user(true);UUID second=payment(passenger,ride(nextDriver,true,2));result=checkout(passenger,second,true);assertEquals(50,result.path("creditCents").asInt());assertEquals(50,ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(200,webhook(event(second,"approved")).statusCode());confirmed(second,1);assertEquals(0,ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));
    }
    @Test void twoConcurrentPaidPassengersCompeteForLastSeatAndLoserGetsFullExternalCredit() throws Exception {
        var driver=user(true);var first=user(false);var second=user(false);UUID ride=ride(driver,true,1),a=payment(first,ride),b=payment(second,ride);checkout(first,a,false);checkout(second,b,false);var ea=event(a,"approved");var eb=event(b,"approved");var gate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(() -> { gate.await();return webhook(ea); });var two=executor.submit(() -> { gate.await();return webhook(eb); });gate.countDown();assertEquals(200,one.get(20,TimeUnit.SECONDS).statusCode());assertEquals(200,two.get(20,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE ride_id=?",Integer.class,ride));assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id IN (?,?) AND kind='receipt'",Integer.class,a,b));assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id IN (?,?) AND kind='receipt_credit'",Integer.class,a,b));
        UUID loser=sql.queryForObject("SELECT payment_id FROM ledger_transactions WHERE payment_id IN (?,?) AND kind='receipt_credit'",UUID.class,a,b);Session owner=loser.equals(a)?first:second;assertEquals(sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,loser).longValue(),ledger.balance(owner.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void failedPaymentThenLateApprovalCreditsMoneyAndRetainsIdempotentReceipts() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));checkout(passenger,payment,false);var failed=event(payment,"failed");assertEquals(200,webhook(failed).statusCode());assertEquals("failed",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));assertEquals("payment_failed",sql.queryForObject("SELECT q.reason FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE p.id=?",String.class,payment));assertEquals(0,ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertTrue(tree(webhook(failed)).path("duplicate").asBoolean());
        var approved=event(payment,"approved");assertEquals(200,webhook(approved).statusCode());assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));assertEquals(sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment).longValue(),ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void expiryCancellationBlockingAndSpentCreditRedirectOnlyExternalMoneyToCredit() throws Exception {
        for(String condition:List.of("expiry","cancellation","blocked","spent_credit","driver_review")) {
            provider.reset();var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));if(condition.equals("spent_credit")) credit(passenger,50);checkout(passenger,payment,true);var event=event(payment,"approved");
            switch(condition) {
                case "expiry" -> sql.update("UPDATE payments SET expires_at=now()-interval '1 second' WHERE id=?",payment);
                case "cancellation" -> { UUID request=sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,payment);assertEquals(200,send("POST",REQUESTS+"/"+request+"/cancel",Map.of(),passenger,key()).statusCode()); }
                case "blocked" -> sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());
                case "spent_credit" -> tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer("synthetic-spend-"+UUID.randomUUID(),"synthetic_spend","Synthetic competing credit spend",List.of(new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,-50),new Posting(null,AccountKind.RETAINED,50)))));
                case "driver_review" -> sql.update("UPDATE driver_profiles SET status='rejected' WHERE user_id=?",driver.id());
            }
            assertEquals(200,webhook(event).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));assertEquals(0,sql.queryForObject("SELECT credit_cents FROM payments WHERE id=?",Integer.class,payment));assertEquals(event.amountCents().longValue(),ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
        }
    }
    @Test void invalidSignedAmountPersistsFailedReceiptWhichCanBeRetriedWithExactPayload() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));checkout(passenger,payment,false);var peer=event(payment,"approved");var bad=new PaymentDto.Event(peer.eventId(),peer.paymentId(),peer.reference(),peer.amountCents()+1,"approved");code(webhook(bad),422,"WEBHOOK_MISMATCH");assertEquals("failed",sql.queryForObject("SELECT status FROM webhook_events WHERE provider='development' AND event_id=?",String.class,bad.eventId()));assertEquals("WEBHOOK_MISMATCH",sql.queryForObject("SELECT last_error_code FROM webhook_events WHERE event_id=?",String.class,bad.eventId()));code(webhook(peer),409,"WEBHOOK_CONFLICT");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",Integer.class,payment));assertEquals(200,webhook(new PaymentDto.Event("corrected-"+UUID.randomUUID(),peer.paymentId(),peer.reference(),peer.amountCents(),"approved")).statusCode());confirmed(payment,1);
    }
    @Test void lostGatewayResponseAndTimeoutReusePreparedPaymentOutsideTransactions() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));String key=key();provider.mode.set(TrackingProvider.Mode.LOST_RESPONSE);code(send("POST",checkoutPath(payment),checkoutInput(false),passenger,key),503,"PAYMENT_PROVIDER_UNAVAILABLE");assertEquals("pending",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));assertEquals(200,send("POST",checkoutPath(payment),checkoutInput(false),passenger,key).statusCode());assertEquals(Set.of(payment),provider.remoteIds);assertEquals(2,provider.calls.get());assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='checkout_prepared' AND resource_id=?",Integer.class,payment));
        provider.reset();var nextDriver=user(true);UUID next=payment(passenger,ride(nextDriver,true,1));provider.mode.set(TrackingProvider.Mode.TIMEOUT);String nextKey=key();code(send("POST",checkoutPath(next),checkoutInput(false),passenger,nextKey),503,"PAYMENT_PROVIDER_UNAVAILABLE");for(int i=0;i<50&&!provider.interrupted.get();i++) Thread.sleep(10);assertTrue(provider.interrupted.get());provider.mode.set(TrackingProvider.Mode.NORMAL);assertEquals(200,send("POST",checkoutPath(next),checkoutInput(false),passenger,nextKey).statusCode());assertEquals(Set.of(next),provider.remoteIds);
    }
    @Test void cancellationCanCommitWhileGatewayIsWaitingAndLateReferenceIsNotAttached() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));UUID request=sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,payment);provider.mode.set(TrackingProvider.Mode.BLOCK);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var checkout=executor.submit(() -> send("POST",checkoutPath(payment),checkoutInput(false),passenger,key()));assertTrue(provider.started.await(5,TimeUnit.SECONDS));assertEquals(200,send("POST",REQUESTS+"/"+request+"/cancel",Map.of(),passenger,key()).statusCode());provider.release.countDown();var response=checkout.get(10,TimeUnit.SECONDS);assertEquals(200,response.statusCode());assertEquals("expired",tree(response).path("status").asText());assertTrue(tree(response).path("checkout").isNull());
        } finally { provider.release.countDown(); }
    }
    @Test void monthlyConfirmationCreatesFrozenContractScheduleAndFutureParcelsAtomically() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());UUID payment=monthlyPayment(driver,passenger,routine,mensal(routine));int legs=sql.queryForObject("SELECT count(*) FROM request_lines l JOIN payments p ON p.request_id=l.request_id WHERE p.id=?",Integer.class,payment);int total=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);
        sql.update("UPDATE rides SET price_cents=price_cents+100,commission_bps=2000 WHERE routine_id=?",routine);checkout(passenger,payment,false);assertEquals(200,webhook(event(payment,"approved")).statusCode());confirmed(payment,legs);
        UUID contract=sql.queryForObject("SELECT DISTINCT subscription_id FROM ride_reservations WHERE payment_id=?",UUID.class,payment);assertEquals(total,sql.queryForObject("SELECT total_cents FROM subscriptions WHERE id=?",Integer.class,contract));assertEquals(start,sql.queryForObject("SELECT start_date::text FROM subscriptions WHERE id=?",String.class,contract));assertEquals(legs,sql.queryForObject("SELECT count(*) FROM subscription_schedule WHERE subscription_id=? AND reservation_id IS NOT NULL AND NOT held",Integer.class,contract));assertEquals(0,sql.queryForObject("SELECT count(*) FROM parcels WHERE payment_id=? AND commission_bps<>1000",Integer.class,payment));
    }
    @Test void renewalReleasesPreviousHoldsAndMarksPreviousContractRenewedOnlyOnPayment() throws Exception {
        var driver=user(true);var passenger=user(false);var offer=routine();offer.put("capacity",1);UUID routine=publish(driver,offer);var quote=preview(passenger,routine);UUID previous=subscription(driver,passenger,routine,LocalDate.parse(start).minusDays(1).toString());UUID leg=UUID.fromString(quote.path("lines").get(0).path("rideId").asText());sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",previous,leg);
        var input=mensal(routine);input.put("previousContractId",previous.toString());UUID payment=monthlyPayment(driver,passenger,routine,input);credit(passenger,sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment));assertTrue(checkout(passenger,payment,true).path("confirmed").asBoolean());
        assertEquals("renewed",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,previous));assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,previous));assertEquals(0,sql.queryForObject("SELECT count(*) FROM subscription_schedule WHERE subscription_id=? AND held",Integer.class,previous));assertEquals(previous,sql.queryForObject("SELECT previous_subscription_id FROM subscriptions WHERE id<>? AND routine_id=?",UUID.class,previous,routine));
    }
    @Test void monthlyEligibilityIsRecheckedAtCheckoutAndAtReceipt() throws Exception {
        for(String condition:List.of("paused","class","calendar","renewal")) {
            var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());var input=mensal(routine);UUID previous=null;if(condition.equals("renewal")) { previous=subscription(driver,passenger,routine,LocalDate.parse(start).minusDays(1).toString());input.put("previousContractId",previous.toString()); }
            UUID payment=monthlyPayment(driver,passenger,routine,input);checkout(passenger,payment,false);
            switch(condition) {
                case "paused" -> sql.update("UPDATE routines SET status='paused' WHERE id=?",routine);
                case "class" -> sql.update("UPDATE student_profiles SET class_id=? WHERE user_id=?",otherClass,passenger.id());
                case "calendar" -> { String date=sql.queryForObject("SELECT r.ride_date::text FROM request_lines l JOIN rides r ON r.id=l.ride_id JOIN payments p ON p.request_id=l.request_id WHERE p.id=? LIMIT 1",String.class,payment);sql.update("INSERT INTO calendar_exclusions(university_id,class_id,exclusion_date,title) VALUES(?,?,?::date,'Synthetic new exclusion')",university,classId,date); }
                case "renewal" -> sql.update("UPDATE subscriptions SET renew=false WHERE id=?",previous);
            }
            code(send("POST",checkoutPath(payment),checkoutInput(false),passenger,key()),409,"MONTHLY_UNAVAILABLE");assertEquals(200,webhook(event(payment,"approved")).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));assertEquals(0,sql.queryForObject("SELECT count(*) FROM subscriptions WHERE routine_id=? AND previous_subscription_id IS NOT NULL",Integer.class,routine));
            sql.update("UPDATE calendar_exclusions SET active=false WHERE university_id=?",university);
        }
    }
    @Test void developmentGatewayRequiresOwnedTestCheckoutAndReplaysApproval() throws Exception {
        var driver=user(true);var passenger=user(false);var outsider=user(false);UUID payment=payment(passenger,ride(driver,true,1));String path="/api/v1/development/payments/"+payment+"/result";code(send("POST",path,Map.of("status","approved"),passenger,null),409,"TEST_PAYMENT_UNAVAILABLE");checkout(passenger,payment,false);code(send("POST",path,Map.of("status","approved"),outsider,null),404,"PAYMENT_NOT_FOUND");assertEquals(200,send("POST",path,Map.of("status","approved"),passenger,null).statusCode());assertTrue(tree(send("POST",path,Map.of("status","approved"),passenger,null)).path("duplicate").asBoolean());confirmed(payment,1);
    }
    @Test void independentJavaInstancesCheckoutReadAndWebhookReplayMatchInBothDirections() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));String path="/api/v1/payments/"+payment.toString().toUpperCase(Locale.ROOT)+"/checkout",key=key();
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var reviewedInput=CheckoutTestFixtures.reviewedBody(app,"POST",path,checkoutInput(false));var old=peer.request("POST",path,reviewedInput,headers(passenger,key));assertEquals(200,old.path("status").asInt());var response=send("POST",path,checkoutInput(false),passenger,key);assertEquals(200,response.statusCode());assertEquals(old.path("body"),tree(response));assertEquals(0,provider.calls.get());
            var event=event(payment,"approved");var signed=signedHeaders(event);old=peer.request("POST","/api/v1/webhooks/payments",event,signed);assertEquals(200,old.path("status").asInt());response=webhook(event);assertEquals(200,response.statusCode());assertTrue(tree(response).path("duplicate").asBoolean());confirmed(payment,1);assertEquals(peer.request("GET","/api/v1/payments/"+payment,null,headers(passenger,null)).path("body"),tree(send("GET","/api/v1/payments/"+payment,null,passenger,null)));
            var nextDriver=user(true);UUID next=payment(passenger,ride(nextDriver,true,1));path="/api/v1/payments/"+next.toString().toUpperCase(Locale.ROOT)+"/checkout";key=key();response=send("POST",path,checkoutInput(false),passenger,key);assertEquals(200,response.statusCode());assertEquals("dev_"+next.toString().toUpperCase(Locale.ROOT),tree(response).path("checkout").path("reference").asText());assertEquals(tree(response),peer.request("POST",path,checkoutInput(false),headers(passenger,key)).path("body"));event=event(next,"approved");assertEquals(200,webhook(event).statusCode());assertTrue(peer.request("POST","/api/v1/webhooks/payments",event,signedHeaders(event)).path("body").path("duplicate").asBoolean());confirmed(next,1);
        }
    }
    @Test void validationAndOwnershipAndMethodChoiceRejectInvalidCheckoutWithoutWrites() throws Exception {
        var driver=user(true);var passenger=user(false);var outsider=user(false);UUID payment=payment(passenger,ride(driver,true,1));
        for(Object input:List.of(Map.of(),Map.of("method","cash","useCredit",false),Map.of("method","pix","useCredit","false"),Map.of("method","pix","useCredit",false,"approved",true))) code(send("POST",checkoutPath(payment),input,passenger,key()),422,"VALIDATION_ERROR");
        code(send("POST",checkoutPath(payment),checkoutInput(false),outsider,key()),404,"PAYMENT_NOT_FOUND");code(send("GET","/api/v1/payments/"+payment,null,driver,null),404,"PAYMENT_NOT_FOUND");code(send("POST",checkoutPath(payment),checkoutInput(false),passenger,null),422,"IDEMPOTENCY_REQUIRED");assertEquals("awaiting",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));
        String key=key();assertEquals(200,send("POST",checkoutPath(payment),checkoutInput(false),passenger,key).statusCode());code(send("POST",checkoutPath(payment),Map.of("method","card","useCredit",false),passenger,key),409,"IDEMPOTENCY_CONFLICT");code(send("POST",checkoutPath(payment),Map.of("method","card","useCredit",false),passenger,key()),409,"PAYMENT_STATE");code(send("POST",checkoutPath(payment),checkoutInput(true),passenger,key()),409,"PAYMENT_STATE");
        var invalid=HttpRequest.newBuilder(URI.create(server+"/api/v1/webhooks/payments")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event(payment,"approved")))).build();code(http.send(invalid,HttpResponse.BodyHandlers.ofString()),401,"WEBHOOK_UNAUTHORIZED");sql.update("UPDATE users SET account_status='suspended' WHERE id=?",passenger.id());code(send("GET","/api/v1/payments/"+payment,null,passenger,null),403,"ACCOUNT_RESTRICTED");
    }
    @Test void swaggerShowsCheckoutAndSignedWebhookAndLocalResultWithoutExposingEntities() throws Exception {
        var api=tree(send("GET","/api/docs/json",null,null,null));var paths=api.path("paths");assertTrue(paths.path("/api/v1/payments/{id}").has("get"));var checkout=paths.path("/api/v1/payments/{id}/checkout").path("post");var headers=new HashSet<String>();checkout.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) headers.add(p.path("name").asText()); });assertTrue(headers.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));
        assertEquals("#/components/schemas/PaymentDetail",checkout.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText());var webhook=paths.path("/api/v1/webhooks/payments").path("post");headers.clear();webhook.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) headers.add(p.path("name").asText()); });assertEquals(Set.of("x-webhook-timestamp","x-webhook-signature"),headers);assertEquals("#/components/schemas/PaymentWebhookEvent",webhook.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText());assertTrue(paths.path("/api/v1/development/payments/{id}/result").has("post"));
    }
    @Test void webhookArrivingBeforeProviderReferenceCanRetrySameDurableReceiptAfterCheckoutRecovery() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));String key=key();provider.mode.set(TrackingProvider.Mode.LOST_RESPONSE);code(send("POST",checkoutPath(payment),checkoutInput(false),passenger,key),503,"PAYMENT_PROVIDER_UNAVAILABLE");
        var event=new PaymentDto.Event("early-"+UUID.randomUUID(),payment.toString(),"dev_"+payment,sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,payment),"approved");code(webhook(event),422,"WEBHOOK_MISMATCH");assertEquals("failed",sql.queryForObject("SELECT status FROM webhook_events WHERE event_id=?",String.class,event.eventId()));
        assertEquals(200,send("POST",checkoutPath(payment),checkoutInput(false),passenger,key).statusCode());assertEquals(200,webhook(event).statusCode());assertEquals("processed",sql.queryForObject("SELECT status FROM webhook_events WHERE event_id=?",String.class,event.eventId()));assertNull(sql.queryForObject("SELECT last_error_code FROM webhook_events WHERE event_id=?",String.class,event.eventId()));confirmed(payment,1);
    }
    @Test void newlyEligibleMonthlyLegMissingFromFrozenSnapshotPreventsConfirmation() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());var quote=preview(passenger,routine);String excludedDate=quote.path("lines").get(0).path("date").asText();UUID exclusion=sql.queryForObject("INSERT INTO calendar_exclusions(university_id,class_id,exclusion_date,title) VALUES(?,?,?::date,'Synthetic quoted exclusion') RETURNING id",UUID.class,university,classId,excludedDate);
        try {
            UUID payment=monthlyPayment(driver,passenger,routine,mensal(routine));checkout(passenger,payment,false);sql.update("UPDATE calendar_exclusions SET active=false WHERE id=?",exclusion);code(send("POST",checkoutPath(payment),checkoutInput(false),passenger,key()),409,"MONTHLY_UNAVAILABLE");assertEquals(200,webhook(event(payment,"approved")).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE payment_id=?",Integer.class,payment));assertEquals(sql.queryForObject("SELECT external_cents FROM payments WHERE id=?",Integer.class,payment).longValue(),ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));
        } finally { sql.update("UPDATE calendar_exclusions SET active=false WHERE id=?",exclusion); }
    }
    @Test void fullCreditMonthlyContractsAndPinsRemainReadableByOriginalUnderDifferentJvmTimezones() throws Exception {
        var previous=TimeZone.getDefault();
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            for(String zone:List.of("UTC","Asia/Tokyo","America/Sao_Paulo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine()),payment=monthlyPayment(driver,passenger,routine,mensal(routine));int amount=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);credit(passenger,amount);String key=key();var response=send("POST",checkoutPath(payment),checkoutInput(true),passenger,key);assertEquals(200,response.statusCode());assertTrue(tree(response).path("confirmed").asBoolean());assertEquals(tree(response),peer.request("POST",checkoutPath(payment),checkoutInput(true),headers(passenger,key)).path("body"));
                assertEquals(start,sql.queryForObject("SELECT s.start_date::text FROM subscriptions s JOIN ride_reservations rr ON rr.subscription_id=s.id WHERE rr.payment_id=? LIMIT 1",String.class,payment));
                UUID reservation=sql.queryForObject("SELECT id FROM ride_reservations WHERE payment_id=? LIMIT 1",UUID.class,payment);String pin=app.getBean(CryptoService.class).decrypt(sql.queryForObject("SELECT pin_ciphertext FROM ride_reservations WHERE id=?",String.class,reservation),AuthTestSettings.KEY);var detail=peer.request("GET","/api/v1/reservations/"+reservation,null,headers(passenger,null));assertEquals(200,detail.path("status").asInt());assertEquals(pin,detail.path("body").path("pin").asText());
                var journal=LedgerCommand.receipt(payment,"Pagamento confirmado; parcelas futuras",List.of(new Posting(passenger.id(),AccountKind.FUTURE,amount),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,-amount)));var replay=new LinkedHashMap<String,Object>();replay.put("key",journal.key());replay.putAll(journal.fingerprintBody());var old=peer.journal(replay);assertEquals(200,old.path("status").asInt());assertEquals(sql.queryForObject("SELECT id FROM ledger_transactions WHERE payment_id=?",UUID.class,payment).toString(),old.path("body").path("id").asText());
            }
        } finally { TimeZone.setDefault(previous); }
    }
    @Test void signedWebhookNormalizesTrimmedTextAndWholeFloatAmountExactlyLikeOriginal() throws Exception {
        var driver=user(true);var passenger=user(false);UUID payment=payment(passenger,ride(driver,true,1));checkout(passenger,payment,false);var event=event(payment,"approved");var input=new LinkedHashMap<String,Object>();input.put("status",event.status());input.put("amountCents",event.amountCents().doubleValue());input.put("reference"," \ufeff"+event.reference()+"\u00a0 ");input.put("paymentId",event.paymentId());input.put("eventId",event.eventId());
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/webhooks/payments")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));signedHeaders(event).forEach(request::header);var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());confirmed(payment,1);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var old=peer.request("POST","/api/v1/webhooks/payments",input,signedHeaders(event));assertEquals(200,old.path("status").asInt());assertTrue(old.path("body").path("duplicate").asBoolean());
            for(var invalid:List.of(Map.entry("eventId"," "+event.eventId()+" "),Map.entry("reference"," ".repeat(201)+event.reference()))) {
                var bad=new LinkedHashMap<String,Object>(input);bad.put(invalid.getKey(),invalid.getValue());
                code(send("POST","/api/v1/webhooks/payments",bad,null,null),422,"VALIDATION_ERROR");
                assertEquals(422,peer.request("POST","/api/v1/webhooks/payments",bad,signedHeaders(event)).path("status").asInt());
            }
        }
    }
}
