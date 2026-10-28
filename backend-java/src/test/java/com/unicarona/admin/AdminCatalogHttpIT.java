package com.unicarona.admin;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.payment.service.LedgerService;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Uses actual paid reservations, real HTTP and PostgreSQL financial/state guards. */
class AdminCatalogHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static LedgerService ledger;
    private static TransactionTemplate tx;
    private static UUID university,course,classId,otherClass;
    private static String server,passwordHash,start;
    private static final String PASSWORD="Synthetic contract HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    private record Paid(Session driver,Session passenger,UUID routine,UUID payment,UUID contract,int total,int legs) {}
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        ledger=app.getBean(LedgerService.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1).withDayOfMonth(1).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('admincataloghttpit-university','Contract University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'admincataloghttpit-course','Contract Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void settings() { sql.update("DELETE FROM rate_limits");sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private Session user(boolean driver,UUID selectedClass) throws Exception {
        String email="contract-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic Contract','2000-05-01',now()) RETURNING id",UUID.class,email,passwordHash);
        LegalTestFixtures.acceptedBusinessUser(app,id);
        sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',3,'night','Gama')",id,university,course,selectedClass);
        if(driver) { sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",id);sql.update("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic Car','Black','ABC1D23',4,'approved')",id); }
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")) { var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]); }
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private Session user(boolean driver) throws Exception { return user(driver,classId); }
    private HttpResponse<String> send(String method,String path,Object body,Session user,String key) throws Exception {
        body=com.unicarona.support.CheckoutTestFixtures.reviewedBody(app,method,path,body);
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(user!=null) request.header("Cookie",user.cookie()).header("x-csrf-token",user.csrf());if(key!=null) request.header("idempotency-key",key);
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else request.method(method,HttpRequest.BodyPublishers.noBody());return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private JsonNode ok(HttpResponse<String> response) throws Exception { assertEquals(200,response.statusCode(),response.body());return tree(response); }
    private void code(HttpResponse<String> response,int status,String code) throws Exception { assertEquals(status,response.statusCode(),response.body());assertEquals(code,tree(response).path("code").asText()); }
    private String key() { return "synthetic-contract-"+UUID.randomUUID(); }
    private Map<String,String> headers(Session user,String key) { var result=new HashMap<String,String>();result.put("cookie",user.cookie());result.put("origin",AuthTestSettings.ORIGIN);result.put("x-csrf-token",user.csrf());if(key!=null) result.put("idempotency-key",key);return result; }
    private UUID publish(Session driver) throws Exception {
        var input=Map.of("region","Gama","meeting","Synthetic meeting","direction","ambos","time","18:00","returnTime","21:00","weekdays",List.of(1,3,5),"capacity",4,"distance",10,"automatic",true);
        return UUID.fromString(ok(send("POST","/api/v1/routines",input,driver,key())).path("id").asText());
    }
    private Paid paid() throws Exception { return paid(user(true),user(false)); }
    private Paid paid(Session driver,Session passenger) throws Exception {
        return paid(driver,passenger,publish(driver));
    }
    private Paid paid(Session driver,Session passenger,UUID routine) throws Exception {
        var input=Map.of("type","mensal","routineId",routine.toString(),"startDate",start,"directions","ambos","weekdays",List.of(1,3,5));
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",input,passenger,key())).path("id").asText());
        UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),driver,key())).path("paymentId").asText());
        int total=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);
        tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer("synthetic-funding-"+UUID.randomUUID(),"synthetic_funding","Synthetic contract fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-total),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,total)))));
        assertTrue(ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",true),passenger,key())).path("confirmed").asBoolean());
        UUID contract=sql.queryForObject("SELECT DISTINCT subscription_id FROM ride_reservations WHERE payment_id=?",UUID.class,payment);
        int legs=sql.queryForObject("SELECT count(*) FROM parcels WHERE payment_id=?",Integer.class,payment);
        return new Paid(driver,passenger,routine,payment,contract,total,legs);
    }
    private record Leg(Session driver,Session passenger,UUID ride,UUID reservation,UUID payment,UUID contract,UUID parcel,int cents) {}
    private Leg leg(Paid paid,int offset) {
        return sql.query("SELECT rr.id,rr.ride_id,rr.subscription_id,p.id AS parcel,p.cents FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id JOIN rides r ON r.id=rr.ride_id WHERE rr.payment_id=? ORDER BY r.ride_date,r.departure_time,rr.id LIMIT 1 OFFSET ?",(row,n) -> new Leg(paid.driver(),paid.passenger(),row.getObject("ride_id",UUID.class),row.getObject("id",UUID.class),paid.payment(),row.getObject("subscription_id",UUID.class),row.getObject("parcel",UUID.class),row.getInt("cents")),paid.payment(),offset).getFirst();
    }
    private UUID offer(Session driver,boolean automatic) throws Exception {
        return UUID.fromString(ok(send("POST","/api/v1/rides",Map.of("region","Gama","meeting","Synthetic lifecycle stop","direction","ida","date",start,"time","18:30","capacity",4,"distance",10,"automatic",automatic),driver,key())).path("id").asText());
    }
    private void fund(Session passenger,int cents) {
        tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer("synthetic-lifecycle-fund-"+UUID.randomUUID(),"synthetic_funding","Synthetic lifecycle fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-cents),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,cents)))));
    }
    private Leg avulsa() throws Exception { var driver=user(true);return avulsa(driver,user(false),offer(driver,true)); }
    private Leg avulsa(Session driver,Session passenger,UUID ride) throws Exception {
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",ride),passenger,key())).path("id").asText());UUID payment=sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,request);int cents=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);fund(passenger,cents);
        assertTrue(ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",true),passenger,key())).path("confirmed").asBoolean());
        return sql.query("SELECT rr.id,p.id AS parcel,rr.subscription_id FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=?",(row,n) -> new Leg(driver,passenger,ride,row.getObject("id",UUID.class),payment,row.getObject("subscription_id",UUID.class),row.getObject("parcel",UUID.class),cents),payment).getFirst();
    }
    private String ride(Leg leg,String action) { return "/api/v1/rides/"+leg.ride()+"/"+action; }
    private String reservation(Leg leg,String action) { return "/api/v1/reservations/"+leg.reservation()+(action.isEmpty()?"":"/"+action); }
    private JsonNode detail(Leg leg) throws Exception { return ok(send("GET",reservation(leg,""),null,leg.passenger(),null)); }
    private int count(String query,Object... args) { return sql.queryForObject(query,Integer.class,args); }
    private String state(String table,UUID id) { return sql.queryForObject("SELECT status FROM "+table+" WHERE id=?",String.class,id); }
    private void today(Leg leg) { sql.update("UPDATE rides SET ride_date=(SELECT (now() AT TIME ZONE un.time_zone)::date FROM universities un WHERE un.id=rides.university_id) WHERE id=?",leg.ride()); }
    private String pin(Leg leg) throws Exception { String pin=detail(leg).path("pin").asText();assertTrue(pin.matches("[0-9]{4}"));return pin; }
    private void begin(Leg leg) throws Exception { today(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key())); }
    private void boarded(Leg leg) throws Exception { today(leg);ok(send("POST",reservation(leg,"board"),Map.of("pin",pin(leg)),leg.driver(),key())); }
    private void awaiting(Leg leg) throws Exception { boarded(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key())); }
    @BeforeEach void clearActiveCalendar() { sql.update("UPDATE calendar_exclusions SET active=false WHERE university_id=?",university); }
    private Session admin() throws Exception { var actor=user(false);sql.update("UPDATE users SET role='admin' WHERE id=?",actor.id());return actor; }
    private String pricing() { return "/api/v1/admin/pricing?universityId="+university; }
    private String calendar() { return "/api/v1/admin/calendar?universityId="+university+"&startDate="+start+"&endDate="+LocalDate.parse(start).plusMonths(2); }
    private JsonNode current(Session actor) throws Exception { return ok(send("GET",pricing(),null,actor,null)).path("items").get(0); }
    private Map<String,Object> pricingInput(Session actor) throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("universityId",university.toString());result.put("previousVersionId",current(actor).path("id").asText());result.put("fuelCents",700);result.put("efficiency",10.125);result.put("wearCents",20);result.put("referencePassengers",2);result.put("commissionBps",1100);result.put("reason"," Synthetic pricing calibration ");return result;
    }
    private Map<String,Object> exclusion(String date,boolean wholeCampus) {
        var body=new LinkedHashMap<String,Object>();body.put("universityId",university.toString());body.put("date",date);body.put("title"," Synthetic academic recess ");body.put("scope",wholeCampus?"university":"class");if(!wholeCampus) { body.put("courseId",course.toString());body.put("periodNumber",3);body.put("classLabel","A"); }return body;
    }
    private JsonNode add(Session actor,Map<String,Object> input) throws Exception { return ok(send("POST","/api/v1/admin/calendar",input,actor,key())); }
    private String deactivate(JsonNode event) { return "/api/v1/admin/calendar/"+event.path("id").asText()+"/deactivate"; }
    private Map<String,Object> monthly(UUID routine) { return Map.of("routineId",routine.toString(),"startDate",start,"directions","ambos","weekdays",List.of(1,3,5)); }
    private JsonNode preview(Session actor,UUID routine) throws Exception { return ok(send("POST","/api/v1/subscriptions/preview",monthly(routine),actor,null)); }
    private JsonNode checkout(Session driver,Session passenger,UUID routine,boolean useCredit) throws Exception {
        var body=new LinkedHashMap<>(monthly(routine));body.put("type","mensal");var request=ok(send("POST","/api/v1/ride-requests",body,passenger,key()));var accepted=ok(send("POST","/api/v1/ride-requests/"+request.path("id").asText()+"/respond",Map.of("accept",true),driver,key()));return ok(send("POST","/api/v1/payments/"+accepted.path("paymentId").asText()+"/checkout",Map.of("method","pix","useCredit",useCredit),passenger,key()));
    }
    private void receipt(JsonNode payment) throws Exception {
        var event=new com.unicarona.payment.dto.PaymentDto.Event("synthetic-calendar-"+UUID.randomUUID(),payment.path("id").asText(),payment.path("checkout").path("reference").asText(),payment.path("externalCents").asInt(),"approved");String timestamp=Long.toString(Instant.now().getEpochSecond());String signature=app.getBean(com.unicarona.payment.provider.WebhookAuthentication.class).signature("",timestamp,event);var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/webhooks/payments")).header("Content-Type","application/json").header("x-webhook-timestamp",timestamp).header("x-webhook-signature",signature).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event)));ok(http.send(request.build(),HttpResponse.BodyHandlers.ofString()));
    }
    @Test void allCatalogAndMetricsRoutesArePrivateAndMutationsRequireOriginCsrfAndKey() throws Exception {
        var actor=admin();var student=user(false);for(String path:List.of(pricing(),calendar(),"/api/v1/admin/metrics")) { code(send("GET",path,null,null,null),401,"UNAUTHENTICATED");code(send("GET",path,null,student,null),403,"FORBIDDEN"); }code(send("POST","/api/v1/admin/pricing",pricingInput(actor),student,key()),403,"FORBIDDEN");code(send("POST","/api/v1/admin/calendar",exclusion(start,true),student,key()),403,"FORBIDDEN");code(send("POST","/api/v1/admin/calendar",exclusion(start,true),actor,null),422,"IDEMPOTENCY_REQUIRED");
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/admin/calendar")).header("Content-Type","application/json").header("Cookie",actor.cookie()).header("idempotency-key",key()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(exclusion(start,true))));code(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),403,"ORIGIN_INVALID");request.header("Origin",AuthTestSettings.ORIGIN);code(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),403,"CSRF_INVALID");
    }
    @Test void strictScopeAndPricingSchemasRejectExtraFieldsAndScalarCoercion() throws Exception {
        var actor=admin();var input=exclusion(start,true);input.put("classLabel","A");code(send("POST","/api/v1/admin/calendar",input,actor,key()),422,"VALIDATION_ERROR");input=exclusion(start,false);input.remove("classLabel");code(send("POST","/api/v1/admin/calendar",input,actor,key()),422,"VALIDATION_ERROR");input=exclusion(start,true);input.put("scope","global");code(send("POST","/api/v1/admin/calendar",input,actor,key()),422,"VALIDATION_ERROR");input=exclusion("2026-02-30",true);code(send("POST","/api/v1/admin/calendar",input,actor,key()),422,"VALIDATION_ERROR");input=pricingInput(actor);input.put("discountBps",2000);code(send("POST","/api/v1/admin/pricing",input,actor,key()),422,"VALIDATION_ERROR");input=pricingInput(actor);input.put("fuelCents","700");code(send("POST","/api/v1/admin/pricing",input,actor,key()),422,"VALIDATION_ERROR");input=pricingInput(actor);input.put("efficiency",10.1234);code(send("POST","/api/v1/admin/pricing",input,actor,key()),422,"VALIDATION_ERROR");input=pricingInput(actor);input.put("fuelCents",700.5);code(send("POST","/api/v1/admin/pricing",input,actor,key()),422,"VALIDATION_ERROR");
    }
    @Test void pricingBoundariesAcceptExactIntegerFloatAndRejectInvalidRanges() throws Exception {
        var actor=admin();for(var item:List.of(Map.entry("fuelCents",9),Map.entry("wearCents",2001),Map.entry("referencePassengers",0),Map.entry("commissionBps",9501),Map.entry("efficiency",0))) { var input=pricingInput(actor);input.put(item.getKey(),item.getValue());code(send("POST","/api/v1/admin/pricing",input,actor,key()),422,"VALIDATION_ERROR"); }var input=pricingInput(actor);input.put("fuelCents",700.0);input.put("commissionBps",0);assertEquals(700,ok(send("POST","/api/v1/admin/pricing",input,actor,key())).path("fuelCents").asInt());
    }
    @Test void strictQueriesValidateRequiredCampusDatesPaginationAndUnknownFields() throws Exception {
        var actor=admin();sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university);for(String path:List.of("/api/v1/admin/pricing",pricing()+"&limit=0",pricing()+"&limit=1&limit=2",pricing()+"&unknown=true",calendar()+"&offset=5001","/api/v1/admin/calendar?universityId="+university+"&startDate=2026-02-30&endDate="+start)) code(send("GET",path,null,actor,null),422,"VALIDATION_ERROR");code(send("GET","/api/v1/admin/calendar?universityId="+university+"&startDate="+LocalDate.parse(start).plusDays(1)+"&endDate="+start,null,actor,null),422,"INVALID_DATE_RANGE");code(send("GET","/api/v1/admin/pricing?universityId="+UUID.randomUUID(),null,actor,null),404,"UNIVERSITY_NOT_FOUND");var first=ok(send("GET",pricing()+"&limit=1",null,actor,null));assertEquals(1,first.path("items").size());assertEquals(1,first.path("nextOffset").asInt());assertTrue(ok(send("GET",pricing()+"&offset=5000",null,actor,null)).path("nextOffset").isNull());
    }
    @Test void concurrentDifferentPricingKeysCommitOneVersionAndOneConflict() throws Exception {
        var actor=admin();var input=pricingInput(actor);try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var gate=new CountDownLatch(1);var a=executor.submit(() -> { gate.await();return send("POST","/api/v1/admin/pricing",input,actor,key()); });var b=executor.submit(() -> { gate.await();return send("POST","/api/v1/admin/pricing",input,actor,key()); });gate.countDown();assertEquals(List.of(200,409),List.of(a.get(15,TimeUnit.SECONDS).statusCode(),b.get(15,TimeUnit.SECONDS).statusCode()).stream().sorted().toList()); }assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='pricing_version_created' AND actor_id=?",actor.id()));
    }
    @Test void samePricingKeyReplaysEvenAfterItsExpectedVersionIsNoLongerCurrent() throws Exception {
        var actor=admin();var input=pricingInput(actor);String key=key();var saved=ok(send("POST","/api/v1/admin/pricing",input,actor,key));assertEquals(saved,ok(send("POST","/api/v1/admin/pricing",input,actor,key)));code(send("POST","/api/v1/admin/pricing",input,actor,key()),409,"PRICING_VERSION_CONFLICT");input.put("reason","Synthetic different decision");code(send("POST","/api/v1/admin/pricing",input,actor,key),409,"IDEMPOTENCY_CONFLICT");assertEquals(1000,saved.path("discountBps").asInt());assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='pricing_version_created'",UUID.fromString(saved.path("id").asText())));
    }
    @Test void immutablePricingPreservesOffersAndEveryPaidMonthlyCent() throws Exception {
        var paid=paid();var actor=admin();var peer=sql.queryForList("SELECT rr.id,rr.price_cents,p.cents,p.commission_bps,p.status FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=? ORDER BY rr.id",paid.payment());var rideBefore=sql.queryForMap("SELECT price_cents,commission_bps,pricing_version_id FROM rides WHERE id=?",leg(paid,0).ride());var old=current(actor);var input=pricingInput(actor);var saved=ok(send("POST","/api/v1/admin/pricing",input,actor,key()));assertEquals(saved,current(actor));assertThrows(org.springframework.dao.DataAccessException.class,() -> sql.update("UPDATE pricing_versions SET fuel_cents=800 WHERE id=?",UUID.fromString(old.path("id").asText())));assertThrows(org.springframework.dao.DataAccessException.class,() -> sql.update("DELETE FROM pricing_versions WHERE id=?",UUID.fromString(old.path("id").asText())));assertEquals(peer,sql.queryForList("SELECT rr.id,rr.price_cents,p.cents,p.commission_bps,p.status FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=? ORDER BY rr.id",paid.payment()));assertEquals(rideBefore,sql.queryForMap("SELECT price_cents,commission_bps,pricing_version_id FROM rides WHERE id=?",leg(paid,0).ride()));UUID offer=offer(paid.driver(),true);assertTrue(sql.queryForObject("SELECT price_cents FROM rides WHERE id=?",Integer.class,offer)>((Number)rideBefore.get("price_cents")).intValue());assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void classCalendarExcludesPreviewDaysWithoutHidingOrdinaryOffersOrOtherClasses() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver),offer=offer(driver,true);var before=preview(passenger,routine);String date=before.path("lines").get(0).path("date").asText();var actor=admin();var event=add(actor,exclusion(date,false));assertEquals(classId.toString(),event.path("classId").asText());assertEquals("Contract Course",event.path("course").asText());var after=preview(passenger,routine);assertEquals(before.path("lines").size()-2,after.path("lines").size());for(var line:after.path("lines")) assertNotEquals(date,line.path("date").asText());assertTrue(after.path("excludedDates").size()>0);assertEquals(before.path("lines").size(),preview(user(false,otherClass),publish(user(true,otherClass))).path("lines").size());assertTrue(ok(send("GET","/api/v1/rides?date="+start+"&region=Gama",null,passenger,null)).path("items").toString().contains(offer.toString()));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='calendar_exclusion_created' AND resource_id=?",UUID.fromString(event.path("id").asText())));
    }
    @Test void universityCalendarAppliesToAllClassesAndLeavesClassFieldsNull() throws Exception {
        var actor=admin();var driver=user(true,otherClass);var passenger=user(false,otherClass);UUID routine=publish(driver);String date=preview(passenger,routine).path("lines").get(0).path("date").asText();var saved=add(actor,exclusion(date,true));for(String field:List.of("classId","course","periodNumber","classLabel")) assertTrue(saved.path(field).isNull());for(var line:preview(passenger,routine).path("lines")) assertNotEquals(date,line.path("date").asText());
    }
    @Test void concurrentCalendarKeysNeverCreateTwoActiveEventsForTheSameScope() throws Exception {
        var actor=admin();var input=exclusion(start,true);try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var a=executor.submit(() -> send("POST","/api/v1/admin/calendar",input,actor,key()));var b=executor.submit(() -> send("POST","/api/v1/admin/calendar",input,actor,key()));assertEquals(List.of(200,409),List.of(a.get(15,TimeUnit.SECONDS).statusCode(),b.get(15,TimeUnit.SECONDS).statusCode()).stream().sorted().toList()); }assertEquals(1,count("SELECT count(*) FROM calendar_exclusions WHERE university_id=? AND exclusion_date=?::date AND active AND class_id IS NULL",university,start));
    }
    @Test void deactivationKeepsHistoryAndAllowsANewEventWithoutReactivatingTheOldOne() throws Exception {
        var actor=admin();var input=exclusion(start,false);String key=key();var event=ok(send("POST","/api/v1/admin/calendar",input,actor,key));assertEquals(event,ok(send("POST","/api/v1/admin/calendar",input,actor,key)));code(send("POST","/api/v1/admin/calendar",input,actor,key()),409,"CALENDAR_EXISTS");String deactivateKey=key();var body=Map.of("reason","Synthetic deactivation");var saved=ok(send("POST",deactivate(event),body,actor,deactivateKey));assertFalse(saved.path("active").asBoolean());assertEquals(saved,ok(send("POST",deactivate(event),body,actor,deactivateKey)));code(send("POST",deactivate(event),body,actor,key()),409,"CALENDAR_INACTIVE");var next=add(actor,input);assertNotEquals(event.path("id").asText(),next.path("id").asText());var page=ok(send("GET",calendar(),null,actor,null));assertTrue(page.path("items").toString().contains(event.path("id").asText()));assertTrue(sql.queryForObject("SELECT NOT active AND deactivated_by=? FROM calendar_exclusions WHERE id=?",Boolean.class,actor.id(),UUID.fromString(event.path("id").asText())));code(send("POST","/api/v1/admin/calendar/"+UUID.randomUUID()+"/deactivate",body,actor,key()),404,"CALENDAR_NOT_FOUND");
    }
    @Test void exclusionsAddedAfterPaymentNeverRewritePaidAgendaOrRetainMoney() throws Exception {
        var paid=paid();var before=sql.queryForList("SELECT rr.id,rr.status,p.cents,p.commission_bps,p.status AS parcel_status FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=? ORDER BY rr.id",paid.payment());String date=sql.queryForObject("SELECT ride_date::text FROM rides WHERE id=?",String.class,leg(paid,0).ride());add(admin(),exclusion(date,true));assertEquals(before,sql.queryForList("SELECT rr.id,rr.status,p.cents,p.commission_bps,p.status AS parcel_status FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=? ORDER BY rr.id",paid.payment()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().retained());assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void exclusionBetweenCheckoutAndSignedReceiptCreditsExternalMoneyWithoutUsingAnticipatedCredit() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver);var before=preview(passenger,routine);fund(passenger,100);var payment=checkout(driver,passenger,routine,true);assertTrue(payment.path("externalCents").asInt()>0);assertEquals(100,payment.path("creditCents").asInt());add(admin(),exclusion(before.path("lines").get(0).path("date").asText(),true));receipt(payment);var detail=ok(send("GET","/api/v1/payments/"+payment.path("id").asText(),null,passenger,null));assertFalse(detail.path("confirmed").asBoolean());assertEquals(0,detail.path("creditCents").asInt());assertEquals(100+payment.path("externalCents").asInt(),ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE payment_id=?",UUID.fromString(payment.path("id").asText())));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void deactivationBetweenCheckoutAndReceiptDoesNotSilentlyPayForAnIncompleteAgenda() throws Exception {
        var actor=admin();var driver=user(true);var passenger=user(false);UUID routine=publish(driver);var peer=preview(passenger,routine);var event=add(actor,exclusion(peer.path("lines").get(0).path("date").asText(),false));var payment=checkout(driver,passenger,routine,false);ok(send("POST",deactivate(event),Map.of("reason","Synthetic restore class day"),actor,key()));assertEquals(peer.path("lines").size(),preview(passenger,routine).path("lines").size());receipt(payment);assertFalse(ok(send("GET","/api/v1/payments/"+payment.path("id").asText(),null,passenger,null)).path("confirmed").asBoolean());assertEquals(payment.path("externalCents").asInt(),ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void pricingPayloadsAndListsReplayAcrossJavaInstancesInBothDirections() throws Exception {
        var actor=admin();try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { for(boolean peerFirst:List.of(true,false)) { var body=pricingInput(actor);body.put("fuelCents",700.0);body.put("efficiency",10.0);String key=key();if(peerFirst) { var old=peer.request("POST","/api/v1/admin/pricing",body,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(old.path("body"),ok(send("POST","/api/v1/admin/pricing",body,actor,key))); }else { var saved=ok(send("POST","/api/v1/admin/pricing",body,actor,key));var old=peer.request("POST","/api/v1/admin/pricing",body,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(saved,old.path("body")); }assertEquals(peer.request("GET",pricing()+"&limit=2",null,headers(actor,null)).path("body"),ok(send("GET",pricing()+"&limit=2",null,actor,null))); } }
    }
    @Test void calendarBothScopesAndDeactivationReplayAcrossInstancesWithExactQueryParity() throws Exception {
        var actor=admin();try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { int index=0;for(boolean peerFirst:List.of(true,false)) for(boolean campus:List.of(true,false)) { var body=exclusion(LocalDate.parse(start).plusDays(index++).toString(),campus);if(!campus) body.put("periodNumber",3.0);String key=key();JsonNode saved;if(peerFirst) { var old=peer.request("POST","/api/v1/admin/calendar",body,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());saved=ok(send("POST","/api/v1/admin/calendar",body,actor,key));assertEquals(old.path("body"),saved); }else { saved=ok(send("POST","/api/v1/admin/calendar",body,actor,key));var old=peer.request("POST","/api/v1/admin/calendar",body,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(saved,old.path("body")); }String deactivateKey=key();var reason=Map.of("reason"," Synthetic calendar correction ");var old=peer.request("POST",deactivate(saved),reason,headers(actor,deactivateKey));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),ok(send("POST",deactivate(saved),reason,actor,deactivateKey)));assertEquals(peer.request("GET",calendar()+"&limit=3&offset=1",null,headers(actor,null)).path("body"),ok(send("GET",calendar()+"&limit=3&offset=1",null,actor,null))); } }
    }
    @Test void uppercasePreviousPricingUuidRepresentsTheSameVersionWithoutAFalseConflict() throws Exception {
        var actor=admin();var input=pricingInput(actor);input.put("previousVersionId",input.get("previousVersionId").toString().toUpperCase(Locale.ROOT));String key=key();try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { var saved=ok(send("POST","/api/v1/admin/pricing",input,actor,key));assertEquals(saved,peer.request("POST","/api/v1/admin/pricing",input,headers(actor,key)).path("body")); }
    }
    @Test void lateAuditFailureRollsBackPricingCalendarClassesAndIdempotencyKeys() throws Exception {
        var actor=admin();var price=pricingInput(actor);var body=exclusion(start,false);body.put("periodNumber",99);body.put("classLabel","C");String pricingKey=key(),calendarKey=key();var before=current(actor);sql.execute("CREATE FUNCTION synthetic_admin_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action IN ('pricing_version_created','calendar_exclusion_created') THEN RAISE EXCEPTION 'Synthetic administration failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");sql.execute("CREATE TRIGGER synthetic_admin_audit_failure BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION synthetic_admin_audit_failure()");try { code(send("POST","/api/v1/admin/pricing",price,actor,pricingKey),409,"INVARIANT_VIOLATION");code(send("POST","/api/v1/admin/calendar",body,actor,calendarKey),409,"INVARIANT_VIOLATION"); }finally { sql.execute("DROP TRIGGER synthetic_admin_audit_failure ON audit_logs");sql.execute("DROP FUNCTION synthetic_admin_audit_failure()"); }assertEquals(before,current(actor));assertEquals(0,count("SELECT count(*) FROM classes WHERE course_id=? AND period_number=99 AND label='C'",course));assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key IN (?,?)",actor.id(),pricingKey,calendarKey));ok(send("POST","/api/v1/admin/pricing",price,actor,pricingKey));assertEquals(99,ok(send("POST","/api/v1/admin/calendar",body,actor,calendarKey)).path("periodNumber").asInt());
    }
    @Test void campusDateDoesNotDependOnJvmTimeZoneAndInvalidCourseDoesNotCreateAnEvent() throws Exception {
        var actor=admin();var previous=TimeZone.getDefault();try { sql.update("UPDATE universities SET time_zone='Pacific/Kiritimati' WHERE id=?",university);String today=sql.queryForObject("SELECT (now() AT TIME ZONE time_zone)::date::text FROM universities WHERE id=?",String.class,university);for(String zone:List.of("UTC","Asia/Tokyo","America/Sao_Paulo")) { TimeZone.setDefault(TimeZone.getTimeZone(zone));var input=exclusion(LocalDate.parse(today).minusDays(1).toString(),true);code(send("POST","/api/v1/admin/calendar",input,actor,key()),422,"DATE_PAST");input=exclusion(today,false);input.put("courseId",UUID.randomUUID().toString());code(send("POST","/api/v1/admin/calendar",input,actor,key()),422,"INVALID_COURSE"); }add(actor,exclusion(today,true)); }finally { TimeZone.setDefault(previous);sql.update("UPDATE universities SET time_zone='America/Sao_Paulo' WHERE id=?",university); }
    }
    @Test void processMetricsCountResponsesAndServerErrorsWithoutExposingStudentData() throws Exception {
        var actor=admin();var before=ok(send("GET","/api/v1/admin/metrics",null,actor,null));ok(send("GET","/health",null,null,null));code(send("GET","/api/v1/admin/metrics",null,user(false),null),403,"FORBIDDEN");var body=pricingInput(actor);sql.execute("CREATE FUNCTION synthetic_metrics_error() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='pricing_version_created' THEN RAISE EXCEPTION 'Synthetic metrics failure'; END IF; RETURN NEW; END $$");sql.execute("CREATE TRIGGER synthetic_metrics_error BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION synthetic_metrics_error()");try { code(send("POST","/api/v1/admin/pricing",body,actor,key()),500,"INTERNAL_ERROR"); }finally { sql.execute("DROP TRIGGER synthetic_metrics_error ON audit_logs");sql.execute("DROP FUNCTION synthetic_metrics_error()"); }var after=ok(send("GET","/api/v1/admin/metrics",null,actor,null));assertTrue(after.path("http").path("requests").asLong()>=before.path("http").path("requests").asLong()+4);assertEquals(before.path("http").path("errors").asLong()+1,after.path("http").path("errors").asLong());assertTrue(after.path("http").path("totalMs").asDouble()>before.path("http").path("totalMs").asDouble());assertEquals(Set.of("http","database"),fieldNames(after));assertEquals(Set.of("requests","errors","totalMs"),fieldNames(after.path("http")));assertEquals(Set.of("total","idle","waiting"),fieldNames(after.path("database")));assertTrue(after.path("database").path("total").asInt()>0);try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { var old=peer.request("GET","/api/v1/admin/metrics",null,headers(actor,null));assertEquals(200,old.path("status").asInt());assertEquals(fieldNames(after),fieldNames(old.path("body")));assertEquals(fieldNames(after.path("http")),fieldNames(old.path("body").path("http"))); }
    }
    private Set<String> fieldNames(JsonNode value) { var names=new HashSet<String>();value.fieldNames().forEachRemaining(names::add);return names; }
    @Test void swaggerIncludesQueriesUnionSchemasSecurityAndMutationHeaders() throws Exception {
        var docs=ok(send("GET","/api/docs/json",null,null,null));for(String path:List.of("/api/v1/admin/pricing","/api/v1/admin/calendar","/api/v1/admin/metrics")) assertFalse(docs.path("paths").path(path).path("get").path("security").isEmpty());var calendar=docs.path("components").path("schemas").path("AdminCalendarInput");assertEquals(2,calendar.path("oneOf").size());assertEquals("scope",calendar.path("discriminator").path("propertyName").asText());var required=new HashSet<String>();docs.path("paths").path("/api/v1/admin/calendar").path("get").path("parameters").forEach(p -> { if(p.path("required").asBoolean()) required.add(p.path("name").asText()); });assertTrue(required.containsAll(Set.of("universityId","startDate","endDate")));required.clear();docs.path("paths").path("/api/v1/admin/calendar").path("post").path("parameters").forEach(p -> { if(p.path("required").asBoolean()) required.add(p.path("name").asText()); });assertTrue(required.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));
    }
}
