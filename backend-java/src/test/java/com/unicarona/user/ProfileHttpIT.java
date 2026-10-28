package com.unicarona.user;

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
class ProfileHttpIT {
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
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('profile-university','Contract University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'profile-course','Contract Course') RETURNING id",UUID.class,university);
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
    private void today(Leg leg) { RideFixtureClock.today(sql,leg.ride()); }
    private String pin(Leg leg) throws Exception { String pin=detail(leg).path("pin").asText();assertTrue(pin.matches("[0-9]{4}"));return pin; }
    private void begin(Leg leg) throws Exception { today(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key())); }
    private void boarded(Leg leg) throws Exception { today(leg);ok(send("POST",reservation(leg,"board"),Map.of("pin",pin(leg)),leg.driver(),key())); }
    private void awaiting(Leg leg) throws Exception { boarded(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key())); }
    private JsonNode profile(Session actor) throws Exception { return ok(send("GET","/api/v1/users/me",null,actor,null)); }
    private Map<String,Object> input(Session actor,String classLabel,boolean acknowledge) throws Exception {
        var p=profile(actor);var academic=new LinkedHashMap<String,Object>();academic.put("universityId",p.path("universityId").asText());academic.put("courseId",p.path("courseId").asText());academic.put("region",p.path("region").asText());academic.put("periodStatus",p.path("periodStatus").asText());academic.put("periodNumber",p.path("periodNumber").isNull()?null:p.path("periodNumber").asInt());academic.put("shift",p.path("shift").asText());academic.put("classLabel",classLabel);var body=new LinkedHashMap<String,Object>();body.put("name",p.path("name").asText());body.put("academic",academic);body.put("acknowledgeMonthlyImpact",acknowledge);return body;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> academic(Map<String,Object> input) { return (Map<String,Object>)input.get("academic"); }
    private HttpResponse<String> update(Session actor,Map<String,Object> input,String key) throws Exception { return send("PATCH","/api/v1/users/me",input,actor,key); }
    private void verified(Session actor) { sql.update("UPDATE student_profiles SET verification_status='verified',verification_source='administrative',verified_at=now(),notices=false,withdrawal_cadence='monthly' WHERE user_id=?",actor.id()); }
    @Test void contactRegionAndNameChangesPreserveVerificationSettingsAndPaidMonthly() throws Exception {
        var paid=paid();verified(paid.passenger());var before=sql.queryForMap("SELECT verification_status,verification_source,verified_at FROM student_profiles WHERE user_id=?",paid.passenger().id());var input=input(paid.passenger(),"A",false);input.put("name","  Synthetic updated name  ");input.put("phone","(61) 99999-9999");academic(input).put("region","  Synthetic new region  ");String key=key();var saved=ok(update(paid.passenger(),input,key));assertEquals("Synthetic updated name",saved.path("name").asText());assertEquals("61999999999",saved.path("phone").asText());assertEquals("Synthetic new region",saved.path("region").asText());assertEquals("verified",saved.path("verificationStatus").asText());assertEquals(before,sql.queryForMap("SELECT verification_status,verification_source,verified_at FROM student_profiles WHERE user_id=?",paid.passenger().id()));assertEquals(json.valueToTree(Map.of("notices",false,"withdrawal","monthly")),saved.path("settings"));assertEquals(saved,ok(update(paid.passenger(),input,key)));assertEquals("active",state("subscriptions",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='profile_updated' AND actor_id=?",paid.passenger().id()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void shiftChangeClearsAcademicVerificationWithoutClosingMonthlyContracts() throws Exception {
        var paid=paid();verified(paid.passenger());var input=input(paid.passenger(),"A",false);academic(input).put("shift","morning");var saved=ok(update(paid.passenger(),input,key()));assertEquals("self_reported",saved.path("verificationStatus").asText());assertEquals("morning",saved.path("shift").asText());assertNull(sql.queryForObject("SELECT verification_source FROM student_profiles WHERE user_id=?",String.class,paid.passenger().id()));assertNull(sql.queryForObject("SELECT verified_at FROM student_profiles WHERE user_id=?",java.sql.Timestamp.class,paid.passenger().id()));assertEquals("active",state("subscriptions",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertFalse(json.readTree(sql.queryForObject("SELECT metadata::text FROM audit_logs WHERE action='profile_updated' AND actor_id=?",String.class,paid.passenger().id())).path("classChanged").asBoolean());
    }
    @Test void classChangeRequiresAcknowledgementAndCreditsOnlyFrozenFutureValue() throws Exception {
        var paid=paid();verified(paid.passenger());var input=input(paid.passenger(),"B",false);String key=key();code(update(paid.passenger(),input,key),409,"MONTHLY_IMPACT_REQUIRED");assertEquals("A",profile(paid.passenger()).path("classLabel").asText());assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",paid.passenger().id(),key));input.put("acknowledgeMonthlyImpact",true);var saved=ok(update(paid.passenger(),input,key));assertEquals("B",saved.path("classLabel").asText());assertEquals("self_reported",saved.path("verificationStatus").asText());assertEquals("ended",state("subscriptions",paid.contract()));assertEquals(paid.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='credited' AND commission_cents=0 AND driver_cents=0",paid.payment()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(paid.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(1,count("SELECT count(*) FROM notifications WHERE user_id=? AND ref_id=? AND title='Vínculo mensal encerrado'",paid.passenger().id(),paid.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void driverClassChangeClosesAllMonthlyRelationsAndPausesIncompatibleRoutines() throws Exception {
        var first=paid();var second=paid(first.driver(),user(false),first.routine());var input=input(first.driver(),"B",true);ok(update(first.driver(),input,key()));for(var paid:List.of(first,second)) { assertEquals("ended",state("subscriptions",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT)); }assertEquals("paused",state("routines",first.routine()));assertEquals(0,ledger.balance(first.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void profileChangePreservesStartedBoardedAndAlreadyBlockedPaidEvidence() throws Exception {
        var paid=paid();var active=leg(paid,0);sql.update("UPDATE rides SET status='active' WHERE id=?",active.ride());var boarded=leg(paid,1);boarded(boarded);var contested=leg(paid,2);ok(send("POST",reservation(contested,"contest"),Map.of("reason","Synthetic existing profile dispute"),contested.passenger(),key()));var input=input(paid.passenger(),"B",true);ok(update(paid.passenger(),input,key()));assertEquals("confirmed",state("ride_reservations",active.reservation()));assertEquals("boarded",state("ride_reservations",boarded.reservation()));assertEquals("under_review",state("ride_reservations",contested.reservation()));assertEquals("blocked",state("parcels",contested.parcel()));assertEquals(active.cents()+boarded.cents(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(contested.cents(),ledger.balance(paid.passenger().id(),AccountKind.BLOCKED));assertEquals(paid.total()-active.cents()-boarded.cents()-contested.cents(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void omittedAndNullAcademicOptionalsRetainDifferentFingerprintsAndPhoneOmissionClearsPhone() throws Exception {
        var actor=user(false);sql.update("UPDATE users SET phone='61999999999' WHERE id=?",actor.id());var input=input(actor,null,false);academic(input).put("periodStatus","irregular");academic(input).put("periodNumber",null);String key=key();var saved=ok(update(actor,input,key));assertEquals("",saved.path("phone").asText());assertTrue(saved.path("classId").isNull());assertTrue(saved.path("periodNumber").isNull());assertEquals("",saved.path("classLabel").asText());assertEquals(saved,ok(update(actor,input,key)));var omitted=new LinkedHashMap<String,Object>(input);var changedAcademic=new LinkedHashMap<>(academic(input));changedAcademic.remove("periodNumber");changedAcademic.remove("classLabel");omitted.put("academic",changedAcademic);code(update(actor,omitted,key),409,"IDEMPOTENCY_CONFLICT");ok(update(actor,omitted,key()));
    }
    @Test void exactIntegerFloatAcademicPeriodIsAcceptedButFractionalPeriodIsRejected() throws Exception {
        var actor=user(false);var input=input(actor,"A",false);academic(input).put("periodNumber",3.0);assertEquals(3,ok(update(actor,input,key())).path("periodNumber").asInt());academic(input).put("periodNumber",3.5);code(update(actor,input,key()),422,"VALIDATION_ERROR");
    }
    @Test void validationAndCatalogErrorsRollbackTheWholeProfileAndKey() throws Exception {
        var actor=user(false);var before=profile(actor);var invalid=input(actor,"A",false);invalid.put("role","admin");code(update(actor,invalid,key()),422,"VALIDATION_ERROR");invalid=input(actor,"A",false);academic(invalid).remove("shift");code(update(actor,invalid,key()),422,"VALIDATION_ERROR");invalid=input(actor,"A",false);invalid.put("phone",null);code(update(actor,invalid,key()),422,"VALIDATION_ERROR");invalid=input(actor,"A",false);academic(invalid).put("regionId",null);code(update(actor,invalid,key()),422,"VALIDATION_ERROR");invalid=input(actor,"A",false);academic(invalid).put("courseId",UUID.randomUUID().toString());String key=key();code(update(actor,invalid,key),422,"INVALID_COURSE");assertEquals(before,profile(actor));assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",actor.id(),key));
        var admin=user(false);sql.update("UPDATE users SET role='admin' WHERE id=?",admin.id());code(update(admin,input(admin,"A",false),key()),422,"PROFILE_UNAVAILABLE");sql.update("UPDATE users SET account_status='suspended' WHERE id=?",actor.id());code(update(actor,input(actor,"A",false),key()),403,"ACCOUNT_RESTRICTED");
    }
    @Test void databaseFailureAfterFutureCreditRestoresAcademicDataMoneyContractsAndKey() throws Exception {
        var paid=paid();var before=profile(paid.passenger());var input=input(paid.passenger(),"B",true);String key=key();sql.execute("CREATE FUNCTION synthetic_profile_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='profile_updated' THEN RAISE EXCEPTION 'Synthetic profile audit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");sql.execute("CREATE TRIGGER synthetic_profile_audit_failure BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION synthetic_profile_audit_failure()");try { code(update(paid.passenger(),input,key),409,"INVARIANT_VIOLATION"); }finally { sql.execute("DROP TRIGGER synthetic_profile_audit_failure ON audit_logs");sql.execute("DROP FUNCTION synthetic_profile_audit_failure()"); }assertEquals(before,profile(paid.passenger()));assertEquals("active",state("subscriptions",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",paid.passenger().id(),key));ok(update(paid.passenger(),input,key));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void concurrentSameKeyChangesOnlyCloseEachContractOnce() throws Exception {
        var paid=paid();var input=input(paid.passenger(),"B",true);String key=key();try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var gate=new CountDownLatch(1);var a=executor.submit(() -> { gate.await();return update(paid.passenger(),input,key); });var b=executor.submit(() -> { gate.await();return update(paid.passenger(),input,key); });gate.countDown();assertEquals(ok(a.get(15,TimeUnit.SECONDS)),ok(b.get(15,TimeUnit.SECONDS))); }assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",paid.contract()));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='profile_updated' AND actor_id=?",paid.passenger().id()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void independentJavaInstancesReplayStandardNullableAndOmittedProfileBodiesInBothDirections() throws Exception {
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { for(boolean peerFirst:List.of(true,false)) { var paid=paid();for(int step=0;step<3;step++) { var input=input(paid.passenger(),step==0?"B":null,true);if(step>0) { academic(input).put("periodStatus","irregular");if(step==1) academic(input).put("periodNumber",null);else { academic(input).remove("periodNumber");academic(input).remove("classLabel"); } }input.put("phone","(61) 99999-9999");String key=key();if(peerFirst) { var old=peer.request("PATCH","/api/v1/users/me",input,headers(paid.passenger(),key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(old.path("body"),ok(update(paid.passenger(),input,key))); }else { var result=ok(update(paid.passenger(),input,key));var old=peer.request("PATCH","/api/v1/users/me",input,headers(paid.passenger(),key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(result,old.path("body")); }assertEquals(peer.request("GET","/api/v1/users/me",null,headers(paid.passenger(),null)).path("body"),profile(paid.passenger())); } } }assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void uppercaseAcademicUuidDoesNotInventAMonthlyImpactOrInvalidateVerification() throws Exception {
        var paid=paid();verified(paid.passenger());var input=input(paid.passenger(),"A",false);academic(input).put("universityId",university.toString().toUpperCase(Locale.ROOT));academic(input).put("courseId",course.toString().toUpperCase(Locale.ROOT));String key=key();try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { var saved=ok(update(paid.passenger(),input,key));assertEquals("verified",saved.path("verificationStatus").asText());assertEquals(saved,peer.request("PATCH","/api/v1/users/me",input,headers(paid.passenger(),key)).path("body")); }assertEquals("active",state("subscriptions",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));
    }
    @Test void profileRetriesWithEveryMonthlyParticipantThatAppearsWhileWaitingForLocks() throws Exception {
        var first=paid();var passenger=user(false);var requestInput=Map.of("type","mensal","routineId",first.routine().toString(),"startDate",start,"directions","ambos","weekdays",List.of(1,3,5));
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",requestInput,passenger,key())).path("id").asText());UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),first.driver(),key())).path("paymentId").asText());int cents=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",false),passenger,key()));
        var input=input(first.driver(),"B",true);String key=key();var pending=new java.util.concurrent.atomic.AtomicReference<Future<HttpResponse<String>>>();var marker=new java.util.concurrent.atomic.AtomicReference<java.sql.Timestamp>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tx.executeWithoutResult(ignored -> {
                // These fixture locks permit FK checks but block the profile's FOR UPDATE.
                // Confirm real paid evidence through the financial primitive, with all its rows protected.
                sql.queryForList("SELECT id FROM users WHERE id IN (?,?) ORDER BY id FOR NO KEY UPDATE",first.driver().id(),passenger.id());pending.set(executor.submit(() -> update(first.driver(),input,key)));
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<deadline) { sql.execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FROM users u%' AND query LIKE '%FOR UPDATE OF u%')",Boolean.class))) { waiting=true;break; }try { Thread.sleep(10); }catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); } }assertTrue(waiting,"Profile must reach the ordered participant lock");
                var lockedRequest=app.getBean(com.unicarona.reservation.repository.RideRequestRepository.class).lockById(request).orElseThrow();var lockedPayment=app.getBean(com.unicarona.payment.repository.PaymentRepository.class).lockById(payment).orElseThrow();var lines=app.getBean(com.unicarona.reservation.repository.RequestRulesRepository.class).lockedLines(request);app.getBean(com.unicarona.payment.service.PaymentConfirmation.class).confirm(lockedRequest,lockedPayment,lines);marker.set(sql.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class));
            });ok(pending.get().get(15,TimeUnit.SECONDS));
        }
        assertTrue(sql.queryForObject("SELECT created_at>? FROM idempotency_keys WHERE actor_id=? AND key=? AND scope='profile:update'",Boolean.class,marker.get(),first.driver().id(),key));assertEquals("ended",state("subscriptions",first.contract()));assertEquals(1,count("SELECT count(DISTINCT s.id) FROM subscriptions s JOIN ride_reservations rr ON rr.subscription_id=s.id WHERE rr.payment_id=? AND s.status='ended'",payment));assertEquals(first.total(),ledger.balance(first.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(cents,ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='profile_updated'",first.driver().id()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void swaggerDocumentsTheCompleteProfileBodyAndMutationHeaders() throws Exception {
        var docs=ok(send("GET","/api/docs/json",null,null,null));var operation=docs.path("paths").path("/api/v1/users/me").path("patch");assertTrue(operation.path("requestBody").path("required").asBoolean());var names=new HashSet<String>();operation.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) names.add(p.path("name").asText()); });assertTrue(names.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));assertFalse(docs.path("components").path("schemas").path("ProfileAcademicUpdate").path("properties").path("periodNumber").isMissingNode());
    }
}
