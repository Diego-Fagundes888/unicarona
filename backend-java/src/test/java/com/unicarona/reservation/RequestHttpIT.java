package com.unicarona.reservation;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
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


class RequestHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static UUID university,course,classId,otherClass;
    private static String server,passwordHash,start;
    private static final String PASSWORD="Synthetic request HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1).withDayOfMonth(1).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('request-university','Routine University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'request-course','Routine Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void resetSettings() { sql.update("DELETE FROM rate_limits");sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university); }
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
    private void noFinancialMovement(UUID request) {
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_reservations WHERE request_id=?",Integer.class,request));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions t JOIN payments p ON p.id=t.payment_id WHERE p.request_id=?",Integer.class,request));
    }
    @Test void automaticAvulsaCreatesAwaitingPaymentWithoutTakingSeatOrMovingMoney() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,true,1);String key=key();var response=send("POST",REQUESTS,avulsa(ride),passenger,key);assertEquals(200,response.statusCode(),response.body());var body=tree(response);UUID id=UUID.fromString(body.path("id").asText());
        assertEquals("accepted",body.path("status").asText());assertFalse(body.path("paymentId").isNull());assertEquals(body,tree(send("POST",REQUESTS,avulsa(ride),passenger,key)));
        var payment=sql.queryForMap("SELECT status,provider,amount_cents,credit_cents,external_cents,credit_requested,gateway_environment,paid_at,provider_reference,checkout_url FROM payments WHERE request_id=?",id);
        assertEquals("awaiting",payment.get("status"));assertEquals("development",payment.get("provider"));assertEquals(0,payment.get("credit_cents"));assertEquals(0,payment.get("external_cents"));assertEquals(false,payment.get("credit_requested"));assertNull(payment.get("paid_at"));assertNull(payment.get("provider_reference"));assertNull(payment.get("checkout_url"));
        assertTrue(sql.queryForObject("SELECT p.amount_cents=l.cents AND l.cents=r.price_cents AND l.commission_bps=r.commission_bps FROM payments p JOIN request_lines l ON l.request_id=p.request_id JOIN rides r ON r.id=l.ride_id WHERE p.request_id=?",Boolean.class,id));
        assertTrue(sql.queryForObject("SELECT abs(extract(epoch FROM(q.expires_at-q.created_at))-86400)<1 AND abs(extract(epoch FROM(p.expires_at-q.created_at))-1800)<1 FROM ride_requests q JOIN payments p ON p.request_id=q.id WHERE q.id=?",Boolean.class,id));
        noFinancialMovement(id);UUID second=request(user(false),avulsa(ride));noFinancialMovement(second);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_created'",Integer.class,id));assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE ref_id=? AND user_id=?",Integer.class,id,driver.id()));
    }
    @Test void manualAcceptanceFreezesQuoteAndCommissionAndCapsPaymentExpiry() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,2),id=request(passenger,avulsa(ride));
        assertEquals("pending",sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));
        int cents=sql.queryForObject("SELECT cents FROM request_lines WHERE request_id=?",Integer.class,id),bps=sql.queryForObject("SELECT commission_bps FROM request_lines WHERE request_id=?",Integer.class,id);
        sql.update("UPDATE rides SET price_cents=price_cents+100,commission_bps=2000 WHERE id=?",ride);sql.update("UPDATE ride_requests SET expires_at=date_trunc('milliseconds',now()+interval '5 minutes') WHERE id=?",id);
        String path=REQUESTS+"/"+id.toString().toUpperCase(Locale.ROOT)+"/respond",key=key();var response=send("POST",path,Map.of("accept",true),driver,key);assertEquals(200,response.statusCode(),response.body());assertEquals(id.toString().toUpperCase(Locale.ROOT),tree(response).path("id").asText());assertEquals("accepted",tree(response).path("status").asText());
        assertEquals(tree(response),tree(send("POST",path,Map.of("accept",true),driver,key)));assertEquals(cents,sql.queryForObject("SELECT amount_cents FROM payments WHERE request_id=?",Integer.class,id));assertEquals(bps,sql.queryForObject("SELECT commission_bps FROM request_lines WHERE request_id=?",Integer.class,id));
        assertTrue(sql.queryForObject("SELECT p.expires_at=q.expires_at FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE q.id=?",Boolean.class,id));noFinancialMovement(id);
        code(send("POST",path,Map.of("accept",false),driver,key),409,"IDEMPOTENCY_CONFLICT");code(send("POST",path,Map.of("accept",true),driver,key()),409,"REQUEST_STATE");
    }
    @Test void rejectionAndCancellationPreserveAtomicNotificationsAndReplays() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,2),id=request(passenger,avulsa(ride));String path=REQUESTS+"/"+id+"/respond",key=key();var rejected=send("POST",path,Map.of("accept",false),driver,key);assertEquals(200,rejected.statusCode());assertEquals("rejected",tree(rejected).path("status").asText());assertTrue(tree(rejected).path("paymentId").isNull());assertEquals(tree(rejected),tree(send("POST",path,Map.of("accept",false),driver,key)));
        code(send("POST",REQUESTS+"/"+id+"/cancel",Map.of(),passenger,key()),409,"REQUEST_STATE");assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));
        UUID next=request(passenger,avulsa(ride));assertEquals(200,send("POST",REQUESTS+"/"+next+"/respond",Map.of("accept",true),driver,key()).statusCode());
        sql.update("UPDATE payments SET status='pending',external_cents=amount_cents WHERE request_id=?",next);
        path=REQUESTS+"/"+next.toString().toUpperCase(Locale.ROOT)+"/cancel";key=key();var cancelled=send("POST",path,Map.of(),passenger,key);assertEquals(200,cancelled.statusCode(),cancelled.body());assertEquals(tree(cancelled),tree(send("POST",path,Map.of(),passenger,key)));assertEquals("expired",sql.queryForObject("SELECT status FROM payments WHERE request_id=?",String.class,next));assertEquals("cancelled",sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,next));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_cancelled'",Integer.class,next));assertEquals(2,sql.queryForObject("SELECT count(*) FROM notifications WHERE ref_id=? AND user_id=?",Integer.class,next,driver.id()));noFinancialMovement(next);
        UUID pending=request(passenger,avulsa(ride));assertEquals(200,send("POST",REQUESTS+"/"+pending+"/cancel",Map.of(),driver,key()).statusCode());
    }
    @Test void monthlySnapshotNeedsExplicitAcceptanceEvenWithAutomaticRoutine() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());var quote=preview(passenger,routine);UUID id=request(passenger,mensal(routine));
        assertEquals("pending",sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));
        var snapshot=json.readTree(sql.queryForObject("SELECT preview::text FROM ride_requests WHERE id=?",String.class,id));assertEquals(quote,snapshot);assertEquals(quote.path("lines").size(),sql.queryForObject("SELECT count(*) FROM request_lines WHERE request_id=?",Integer.class,id));
        sql.update("UPDATE rides SET price_cents=price_cents+100,commission_bps=2000 WHERE routine_id=?",routine);
        var accepted=send("POST",REQUESTS+"/"+id+"/respond",Map.of("accept",true),driver,key());assertEquals(200,accepted.statusCode(),accepted.body());assertEquals(quote.path("totalCents").asInt(),sql.queryForObject("SELECT amount_cents FROM payments WHERE request_id=?",Integer.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM request_lines WHERE request_id=? AND commission_bps<>1000",Integer.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM subscriptions WHERE routine_id=?",Integer.class,routine));noFinancialMovement(id);
    }
    @Test void monthlyRejectsChangedAcademicCombinationAndUnavailableLegWithoutPartialAcceptance() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine()),id=request(passenger,mensal(routine));String path=REQUESTS+"/"+id+"/respond",key=key();
        sql.update("UPDATE student_profiles SET class_id=? WHERE user_id=?",otherClass,passenger.id());code(send("POST",path,Map.of("accept",true),driver,key),422,"CLASS_MISMATCH");sql.update("UPDATE student_profiles SET class_id=? WHERE user_id=?",classId,passenger.id());
        UUID leg=sql.queryForObject("SELECT ride_id FROM request_lines WHERE request_id=? LIMIT 1",UUID.class,id);sql.update("UPDATE rides SET proposed_time='18:45' WHERE id=?",leg);code(send("POST",path,Map.of("accept",true),driver,key),409,"RIDE_FULL");
        assertEquals("pending",sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_accepted'",Integer.class,id));
        sql.update("UPDATE rides SET proposed_time=null WHERE id=?",leg);assertEquals(200,send("POST",path,Map.of("accept",true),driver,key).statusCode());
    }
    @Test void readsAndIdempotentMutationsReplayAcrossIndependentJavaInstances() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,2);var input=avulsa(ride);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            String oldKey=key();var old=peer.request("POST",REQUESTS,input,headers(passenger,oldKey));assertEquals(200,old.path("status").asInt());var replay=send("POST",REQUESTS,input,passenger,oldKey);assertEquals(200,replay.statusCode());assertEquals(old.path("body"),tree(replay));UUID id=UUID.fromString(tree(replay).path("id").asText());
            String path=REQUESTS+"/"+id.toString().toUpperCase(Locale.ROOT)+"/respond",newKey=key();var accepted=send("POST",path,Map.of("accept",true),driver,newKey);assertEquals(200,accepted.statusCode());assertEquals(tree(accepted),peer.request("POST",path,Map.of("accept",true),headers(driver,newKey)).path("body"));
            for(String query:List.of(REQUESTS,REQUESTS+"?side=driver&status=accepted&limit=1",REQUESTS+"?limit=1&offset=1",REQUESTS+"/"+id)) {
                var actor=query.contains("side=driver")?driver:passenger;assertEquals(peer.request("GET",query,null,headers(actor,null)).path("body"),tree(send("GET",query,null,actor,null)));
            }
            path=REQUESTS+"/"+id+"/cancel";oldKey=key();old=peer.request("POST",path,Map.of(),headers(passenger,oldKey));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),tree(send("POST",path,Map.of(),passenger,oldKey)));
            newKey=key();var created=send("POST",REQUESTS,input,passenger,newKey);assertEquals(200,created.statusCode());assertEquals(tree(created),peer.request("POST",REQUESTS,input,headers(passenger,newKey)).path("body"));
            UUID second=UUID.fromString(tree(created).path("id").asText());path=REQUESTS+"/"+second+"/respond";oldKey=key();old=peer.request("POST",path,Map.of("accept",false),headers(driver,oldKey));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),tree(send("POST",path,Map.of("accept",false),driver,oldKey)));
        }
    }
    @Test void monthlyHashesAndSnapshotMatchOriginalIncludingWholeFloatWeekdaysAndOptionalRenewal() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());var input=mensal(routine);input.put("weekdays",List.of(5.0,1.0,3.0));
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            String oldKey=key();var old=peer.request("POST",REQUESTS,input,headers(passenger,oldKey));assertEquals(200,old.path("status").asInt());var replay=send("POST",REQUESTS,input,passenger,oldKey);assertEquals(200,replay.statusCode());assertEquals(old.path("body"),tree(replay));
            UUID id=UUID.fromString(tree(replay).path("id").asText());assertEquals(peer.request("GET",REQUESTS+"/"+id,null,headers(passenger,null)).path("body"),tree(send("GET",REQUESTS+"/"+id,null,passenger,null)));
            var second=user(false);String newKey=key();var created=send("POST",REQUESTS,input,second,newKey);assertEquals(200,created.statusCode());assertEquals(tree(created),peer.request("POST",REQUESTS,input,headers(second,newKey)).path("body"));
            assertEquals(200,send("POST",REQUESTS+"/"+tree(created).path("id").asText()+"/cancel",Map.of(),second,key()).statusCode());
            String previousEnd=LocalDate.parse(start).minusDays(1).toString();UUID previous=subscription(driver,second,routine,previousEnd);input.put("previousContractId",previous.toString());newKey=key();created=send("POST",REQUESTS,input,second,newKey);assertEquals(200,created.statusCode(),created.body());assertEquals(tree(created),peer.request("POST",REQUESTS,input,headers(second,newKey)).path("body"));
        }
    }
    @Test void ownershipRestrictionAndBilateralBlocksAreEnforcedWithoutLeakingDetails() throws Exception {
        var driver=user(true);var passenger=user(false);var outsider=user(false);UUID ride=ride(driver,false,2),id=request(passenger,avulsa(ride));String detail=REQUESTS+"/"+id;
        code(send("GET",detail,null,outsider,null),404,"REQUEST_NOT_FOUND");code(send("POST",detail+"/respond",Map.of("accept",true),passenger,key()),404,"REQUEST_NOT_FOUND");code(send("POST",detail+"/cancel",Map.of(),outsider,key()),404,"REQUEST_NOT_FOUND");
        assertEquals(0,tree(send("GET",REQUESTS,null,outsider,null)).path("items").size());sql.update("UPDATE users SET account_status='suspended' WHERE id=?",passenger.id());
        assertEquals(200,send("GET",detail,null,passenger,null).statusCode());code(send("GET",REQUESTS,null,passenger,null),403,"ACCOUNT_RESTRICTED");code(send("POST",detail+"/cancel",Map.of(),passenger,key()),403,"ACCOUNT_RESTRICTED");
        sql.update("UPDATE users SET account_status='active' WHERE id=?",passenger.id());sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());
        code(send("POST",detail+"/respond",Map.of("accept",true),driver,key()),409,"COMBINATION_UNAVAILABLE");assertEquals(200,send("GET",detail,null,passenger,null).statusCode());assertEquals(200,send("POST",detail+"/cancel",Map.of(),passenger,key()).statusCode());
    }
    @Test void expiredRequestsAndDuplicateActiveRequestsRollbackAllWrites() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,2),id=request(passenger,avulsa(ride));
        code(send("POST",REQUESTS,avulsa(ride),passenger,key()),409,"CONFLICT");assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_requests WHERE passenger_id=?",Integer.class,passenger.id()));
        sql.update("UPDATE ride_requests SET expires_at=now()-interval '1 second' WHERE id=?",id);String path=REQUESTS+"/"+id+"/respond",key=key();code(send("POST",path,Map.of("accept",true),driver,key),409,"REQUEST_STATE");assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));
        assertEquals(200,send("POST",REQUESTS+"/"+id+"/cancel",Map.of(),driver,key()).statusCode());noFinancialMovement(id);
        code(send("POST",REQUESTS,avulsa(UUID.randomUUID()),passenger,key()),404,"RIDE_NOT_FOUND");code(send("POST",REQUESTS,avulsa(ride),driver,key()),409,"RIDE_UNAVAILABLE");
    }
    @Test void strictBodiesQueriesAndMutationAuthenticationRejectInvalidInputs() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,2),id=request(passenger,avulsa(ride));
        for(Object input:List.of(Map.of(),Map.of("type","unknown","rideId",ride),Map.of("type","avulsa"),Map.of("type","avulsa","rideId","invalid"),Map.of("type","avulsa","rideId",ride,"extra",true),Map.of("type","avulsa","rideId",ride,"routineId",UUID.randomUUID()))) code(send("POST",REQUESTS,input,passenger,key()),422,"VALIDATION_ERROR");
        for(Object input:List.of(Map.of(),Map.of("accept","true"),Map.of("accept",1),Map.of("accept",true,"extra",1))) code(send("POST",REQUESTS+"/"+id+"/respond",input,driver,key()),422,"VALIDATION_ERROR");
        code(send("POST",REQUESTS+"/"+id+"/cancel",Map.of("reason","unwanted"),passenger,key()),422,"VALIDATION_ERROR");
        for(String query:List.of("side=other","status=paid","side=driver&side=passenger","unexpected=1","limit=0","offset=-1")) code(send("GET",REQUESTS+"?"+query,null,passenger,null),422,"VALIDATION_ERROR");
        code(send("GET",REQUESTS+"/invalid",null,passenger,null),422,"VALIDATION_ERROR");code(send("GET",REQUESTS,null,null,null),401,"UNAUTHENTICATED");code(send("POST",REQUESTS,avulsa(ride),passenger,null),422,"IDEMPOTENCY_REQUIRED");
        var unsafe=HttpRequest.newBuilder(URI.create(server+REQUESTS+"/"+id+"/cancel")).header("Origin",AuthTestSettings.ORIGIN).header("Cookie",passenger.cookie()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();code(http.send(unsafe,HttpResponse.BodyHandlers.ofString()),403,"CSRF_INVALID");
    }
    @Test void concurrentCreateAndResponseReplayOnceWhileAcceptedRequestsDoNotOccupySeats() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,1);String key=key();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(() -> { gate.await();return send("POST",REQUESTS,avulsa(ride),passenger,key); });var second=pool.submit(() -> { gate.await();return send("POST",REQUESTS,avulsa(ride),passenger,key); });gate.countDown();var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);assertEquals(200,a.statusCode());assertEquals(200,b.statusCode());assertEquals(tree(a),tree(b));
            UUID id=UUID.fromString(tree(a).path("id").asText());String path=REQUESTS+"/"+id+"/respond",respondKey=key();var responseGate=new CountDownLatch(1);first=pool.submit(() -> { responseGate.await();return send("POST",path,Map.of("accept",true),driver,respondKey); });second=pool.submit(() -> { responseGate.await();return send("POST",path,Map.of("accept",true),driver,respondKey); });responseGate.countDown();a=first.get(20,TimeUnit.SECONDS);b=second.get(20,TimeUnit.SECONDS);assertEquals(200,a.statusCode());assertEquals(200,b.statusCode());assertEquals(tree(a),tree(b));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_accepted'",Integer.class,id));noFinancialMovement(id);
        }
        UUID next=request(user(false),avulsa(ride));assertEquals(200,send("POST",REQUESTS+"/"+next+"/respond",Map.of("accept",true),driver,key()).statusCode());
    }
    @Test void swaggerDocumentsBothRequestVariantsAndEveryMutationHeader() throws Exception {
        var api=tree(send("GET","/api/docs/json",null,null,null));var paths=api.path("paths");for(String path:List.of(REQUESTS,REQUESTS+"/{id}/respond",REQUESTS+"/{id}/cancel")) {
            var operation=paths.path(path).path("post");assertFalse(operation.isMissingNode());assertTrue(operation.path("security").get(0).has("cookieAuth"));var names=new HashSet<String>();operation.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) names.add(p.path("name").asText()); });assertTrue(names.containsAll(Set.of("idempotency-key","Origin","x-csrf-token")));
        }
        assertTrue(paths.path(REQUESTS).has("get"));assertTrue(paths.path(REQUESTS+"/{id}").has("get"));var names=new HashSet<String>();paths.path(REQUESTS).path("get").path("parameters").forEach(p -> names.add(p.path("name").asText()));assertTrue(names.containsAll(Set.of("side","status","limit","offset")));
        assertEquals("#/components/schemas/RideRequestCreate",paths.path(REQUESTS).path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText());
        var schema=api.path("components").path("schemas").path("RideRequestCreate");assertEquals("type",schema.path("discriminator").path("propertyName").asText());var variants=new HashSet<String>();schema.path("oneOf").forEach(s -> variants.add(s.path("$ref").asText()));assertEquals(Set.of("#/components/schemas/RideRequestAvulsa","#/components/schemas/RideRequestMensal"),variants);
    }
    @Test void heldSeatPreventsCreationAndLaterAcceptanceAndFailuresCanReuseTheirKey() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=ride(driver,false,1),routine=publish(driver,routine());sql.update("UPDATE rides SET routine_id=? WHERE id=?",routine,ride);
        UUID held=subscription(driver,user(false),routine,LocalDate.parse(start).plusMonths(1).toString());sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",held,ride);
        String key=key();code(send("POST",REQUESTS,avulsa(ride),passenger,key),409,"RIDE_FULL");assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_requests WHERE passenger_id=?",Integer.class,passenger.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",Integer.class,passenger.id(),key));
        sql.update("UPDATE subscription_schedule SET held=false WHERE subscription_id=?",held);var created=send("POST",REQUESTS,avulsa(ride),passenger,key);assertEquals(200,created.statusCode());UUID id=UUID.fromString(tree(created).path("id").asText());
        sql.update("UPDATE subscription_schedule SET held=true WHERE subscription_id=?",held);String path=REQUESTS+"/"+id+"/respond",respondKey=key();code(send("POST",path,Map.of("accept",true),driver,respondKey),409,"RIDE_FULL");assertEquals("pending",sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE request_id=?",Integer.class,id));
        sql.update("UPDATE subscription_schedule SET held=false WHERE subscription_id=?",held);assertEquals(200,send("POST",path,Map.of("accept",true),driver,respondKey).statusCode());noFinancialMovement(id);
    }
    @Test void monthlyCreationRejectsIncompleteScheduleAndRollsBackRequestLinesAndKey() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());LocalDate day=LocalDate.parse(start);while(!Set.of(1,3,5).contains(day.getDayOfWeek().getValue()%7)) day=day.plusDays(1);
        UUID leg=sql.queryForObject("INSERT INTO rides(university_id,route_id,routine_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id,automatic,status) SELECT rt.university_id,rt.route_id,rt.id,rt.driver_id,rt.vehicle_id,'ida',?::date,rt.ida_time,1,rt.ida_cents,p.commission_bps,rt.pricing_version_id,rt.automatic,'planned' FROM routines rt JOIN pricing_versions p ON p.id=rt.pricing_version_id WHERE rt.id=? RETURNING id",UUID.class,day.toString(),routine);
        UUID held=subscription(driver,user(false),routine,LocalDate.parse(start).plusMonths(1).toString());sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",held,leg);
        String key=key();code(send("POST",REQUESTS,mensal(routine),passenger,key),409,"MONTHLY_CONFLICT");assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_requests WHERE passenger_id=?",Integer.class,passenger.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",Integer.class,passenger.id(),key));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=? AND ride_date>=?::date",Integer.class,routine,start));
        sql.update("UPDATE subscription_schedule SET held=false WHERE subscription_id=?",held);var created=send("POST",REQUESTS,mensal(routine),passenger,key);assertEquals(200,created.statusCode());UUID id=UUID.fromString(tree(created).path("id").asText());noFinancialMovement(id);
    }
    @Test void monthlyRequestCanBePendingDuringTimeProposalButAcceptanceChecksItAgain() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());var quoted=preview(passenger,routine);UUID leg=UUID.fromString(quoted.path("lines").get(0).path("rideId").asText());sql.update("UPDATE rides SET proposed_time='18:45' WHERE id=?",leg);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            String key=key();var old=peer.request("POST",REQUESTS,mensal(routine),headers(passenger,key));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),tree(send("POST",REQUESTS,mensal(routine),passenger,key)));UUID id=UUID.fromString(old.path("body").path("id").asText());assertEquals("pending",old.path("body").path("status").asText());
            code(send("POST",REQUESTS+"/"+id+"/respond",Map.of("accept",true),driver,key()),409,"RIDE_FULL");noFinancialMovement(id);
        }
    }
    @Test void monthlyUnionRejectsMalformedDatesNullOptionalFieldsAndNonIntegerWeekdays() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver,routine());
        for(String field:List.of("routineId","startDate","directions","weekdays")) { var input=mensal(routine);input.remove(field);code(send("POST",REQUESTS,input,passenger,key()),422,"VALIDATION_ERROR"); }
        for(Object days:List.of(List.of(),List.of(1,1),List.of(7),List.of(-1),List.of(1.5),List.of("1"),Arrays.asList((Object)null))) { var input=mensal(routine);input.put("weekdays",days);code(send("POST",REQUESTS,input,passenger,key()),422,"VALIDATION_ERROR"); }
        for(String date:List.of("2026-02-29","2026-13-01","26-01-01")) { var input=mensal(routine);input.put("startDate",date);code(send("POST",REQUESTS,input,passenger,key()),422,"VALIDATION_ERROR"); }
        var input=mensal(routine);input.put("previousContractId",null);code(send("POST",REQUESTS,input,passenger,key()),422,"VALIDATION_ERROR");input=mensal(routine);input.put("rideId",UUID.randomUUID());code(send("POST",REQUESTS,input,passenger,key()),422,"VALIDATION_ERROR");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_requests WHERE passenger_id=?",Integer.class,passenger.id()));
    }
}
