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
class AdminAccountHttpIT {
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
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('adminaccounthttpit-university','Contract University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'adminaccounthttpit-course','Contract Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void settings() { sql.update("DELETE FROM rate_limits");sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private Session user(boolean driver,UUID selectedClass) throws Exception {
        String email="contract-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic Contract','2000-05-01',now()) RETURNING id",UUID.class,email,passwordHash);
        com.unicarona.support.LegalTestFixtures.acceptedBusinessUser(app,id);
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
        String departure=LocalTime.of(18,30).plusMinutes(count("SELECT count(*) FROM rides WHERE driver_id=?",driver.id())).toString();return UUID.fromString(ok(send("POST","/api/v1/rides",Map.of("region","Gama","meeting","Synthetic lifecycle stop","direction","ida","date",start,"time",departure,"capacity",4,"distance",10,"automatic",automatic),driver,key())).path("id").asText());
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
    private Session admin() throws Exception { var actor=user(false);sql.update("UPDATE users SET role='admin' WHERE id=?",actor.id());return actor; }
    private String review(Session actor) { return "/api/v1/admin/accounts/"+actor.id()+"/review"; }
    private Map<String,Object> decision(String status,String expected) { return Map.of("status",status,"expectedStatus",expected,"reason"," Synthetic preventive account review "); }
    private JsonNode suspend(Session actor,Session target) throws Exception { return ok(send("POST",review(target),decision("suspended","active"),actor,key())); }
    private void restore(Session actor,Session target) throws Exception { ok(send("POST",review(target),decision("active","suspended"),actor,key())); }
    private String account(Session actor) { return sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,actor.id()); }
    private Map<String,Object> parcel(UUID id) { return sql.queryForMap("SELECT * FROM parcels WHERE id=?",id); }
    @Test void directoryIsPrivateSearchIgnoresAccentsAndDeletedOrAdminAccountsAreExcluded() throws Exception {
        var actor=admin();var target=user(false);sql.update("UPDATE users SET name='Synthetic Álvaro AdminSearch' WHERE id=?",target.id());var deleted=user(false);sql.update("UPDATE users SET account_status='deleted',deleted_at=now(),name='Synthetic Álvaro AdminSearch' WHERE id=?",deleted.id());code(send("GET","/api/v1/admin/accounts",null,target,null),403,"FORBIDDEN");code(send("GET","/api/v1/admin/accounts",null,null,null),401,"UNAUTHENTICATED");var page=ok(send("GET","/api/v1/admin/accounts?q=alvaro%20adminsearch",null,actor,null));assertEquals(1,page.path("items").size());var item=page.path("items").get(0);assertEquals(target.id().toString(),item.path("id").asText());assertEquals("not_requested",item.path("driverStatus").asText());assertEquals("Contract University",item.path("university").asText());assertFalse(item.has("password_hash"));assertFalse(item.has("phone"));assertTrue(page.path("nextOffset").isNull());assertTrue(ok(send("GET","/api/v1/admin/accounts?limit=1",null,actor,null)).path("nextOffset").asInt()>0);assertEquals(0,ok(send("GET","/api/v1/admin/accounts?offset=5000",null,actor,null)).path("items").size());
    }
    @Test void strictQueriesReviewSchemasAndStatusVersionsRejectInvalidOperations() throws Exception {
        var actor=admin();var target=user(false);for(String path:List.of("/api/v1/admin/accounts?q=%20","/api/v1/admin/accounts?q="+"%20".repeat(81)+"a","/api/v1/admin/accounts?status=deleted","/api/v1/admin/accounts?limit=101","/api/v1/admin/accounts?q=a&q=b","/api/v1/admin/accounts?role=admin")) code(send("GET",path,null,actor,null),422,"VALIDATION_ERROR");var body=new LinkedHashMap<>(decision("suspended","active"));body.put("role","admin");code(send("POST",review(target),body,actor,key()),422,"VALIDATION_ERROR");body=new LinkedHashMap<>(decision("suspended","active"));body.put("reason"," x ");code(send("POST",review(target),body,actor,key()),422,"VALIDATION_ERROR");code(send("POST",review(target),decision("suspended","active"),target,key()),403,"FORBIDDEN");code(send("POST",review(actor),decision("suspended","active"),actor,key()),404,"ACCOUNT_UNAVAILABLE");code(send("POST","/api/v1/admin/accounts/"+UUID.randomUUID()+"/review",decision("suspended","active"),actor,key()),404,"USER_NOT_FOUND");code(send("POST",review(target),decision("active","active"),actor,key()),409,"ACCOUNT_STATE");String key=key();code(send("POST",review(target),decision("suspended","suspended"),actor,key),409,"ACCOUNT_VERSION_CONFLICT");assertEquals("active",account(target));assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",actor.id(),key));
    }
    @Test void suspensionPreservesSettledMoneyAndLetsBoardedRideFinishWhileFutureMoneyNeedsIndependentReview() throws Exception {
        var driver=user(true);var passenger=user(false);var completed=avulsa(driver,passenger,offer(driver,true));awaiting(completed);ok(send("POST",reservation(completed,"final"),Map.of(),passenger,key()));var settled=parcel(completed.parcel());long available=ledger.balance(driver.id(),AccountKind.DRIVER_AVAILABLE);assertTrue(available>0);
        var ongoing=avulsa(driver,passenger,offer(driver,true));boarded(ongoing);ok(send("POST",ride(ongoing,"start"),Map.of(),driver,key()));var future=avulsa(driver,passenger,offer(driver,true));var monthly=paid(driver,passenger);var actor=admin();var reviewed=suspend(actor,driver);assertEquals(monthly.legs()+1,reviewed.path("affectedParcels").asInt());UUID caseId=UUID.fromString(reviewed.path("financialCaseId").asText());assertEquals("financial_pending",state("occurrences",caseId));assertEquals(actor.id(),sql.queryForObject("SELECT reporter_id FROM occurrences WHERE id=?",UUID.class,caseId));assertEquals(monthly.legs()+1,count("SELECT count(*) FROM occurrence_parcels WHERE occurrence_id=?",caseId));assertEquals(settled,parcel(completed.parcel()));assertEquals(available,ledger.balance(driver.id(),AccountKind.DRIVER_AVAILABLE));assertEquals("active",state("rides",ongoing.ride()));assertEquals("boarded",state("ride_reservations",ongoing.reservation()));assertEquals("future",state("parcels",ongoing.parcel()));assertEquals("blocked",state("parcels",future.parcel()));assertEquals("under_review",state("ride_reservations",future.reservation()));assertEquals("cancelled",state("rides",future.ride()));assertEquals("interrupted",state("subscriptions",monthly.contract()));assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,monthly.contract()));assertEquals("paused",state("routines",monthly.routine()));assertEquals(monthly.total()+future.cents(),ledger.balance(passenger.id(),AccountKind.BLOCKED));assertEquals(0,ledger.reconciliation().retained());
        code(send("POST",ride(future,"start"),Map.of(),driver,key()),403,"ACCOUNT_RESTRICTED");
        var validOffer=Map.of("region","Gama","meeting","Synthetic restricted stop","direction","ida","date",start,"time","18:30","capacity",4,"distance",10,"automatic",true);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var oldMalformed=peer.request("POST","/api/v1/rides",Map.of(),headers(driver,key()));assertEquals(422,oldMalformed.path("status").asInt());assertEquals("VALIDATION_ERROR",oldMalformed.path("body").path("code").asText());
            var oldRestricted=peer.request("POST","/api/v1/rides",validOffer,headers(driver,key()));assertEquals(403,oldRestricted.path("status").asInt());assertEquals("ACCOUNT_RESTRICTED",oldRestricted.path("body").path("code").asText());
        }
        code(send("POST","/api/v1/rides",Map.of(),driver,key()),422,"VALIDATION_ERROR");code(send("POST","/api/v1/rides",validOffer,driver,key()),403,"ACCOUNT_RESTRICTED");ok(send("GET","/api/v1/users/me",null,driver,null));ok(send("GET","/api/v1/occurrences",null,driver,null));ok(send("GET","/api/v1/rides/"+ongoing.ride(),null,driver,null));ok(send("POST",ride(ongoing,"finish"),Map.of("availability",false),driver,key()));ok(send("POST",reservation(ongoing,"final"),Map.of(),passenger,key()));assertEquals("released",state("parcels",ongoing.parcel()));restore(actor,driver);assertEquals("cancelled",state("rides",future.ride()));assertEquals("interrupted",state("subscriptions",monthly.contract()));assertEquals("blocked",state("parcels",future.parcel()));assertEquals(monthly.total()+future.cents(),ledger.balance(passenger.id(),AccountKind.BLOCKED));ok(send("POST","/api/v1/admin/occurrences/"+caseId+"/review",Map.of("outcome","credited","reason","Synthetic separate financial decision"),actor,key()));assertEquals("credited",state("parcels",future.parcel()));assertEquals("cancelled",state("ride_reservations",future.reservation()));assertEquals(monthly.total()+future.cents(),ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void passengerSuspensionIsolatesOnlyTheirPaidReservationWithoutCancellingDriversOffers() throws Exception {
        var first=avulsa();var another=avulsa(first.driver(),user(false),first.ride());var actor=admin();var result=suspend(actor,first.passenger());assertEquals(1,result.path("affectedParcels").asInt());assertEquals("planned",state("rides",first.ride()));assertEquals("under_review",state("ride_reservations",first.reservation()));assertEquals("confirmed",state("ride_reservations",another.reservation()));assertEquals("future",state("parcels",another.parcel()));assertEquals(first.cents(),ledger.balance(first.passenger().id(),AccountKind.BLOCKED));assertEquals("active",account(first.driver()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void unpaidPendingAndAcceptedRequestsExpireAndNotifyBothParticipants() throws Exception {
        var driver=user(true);var pendingPassenger=user(false);UUID pending=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",offer(driver,false)),pendingPassenger,key())).path("id").asText());var acceptedPassenger=user(false);var accepted=ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",offer(driver,true)),acceptedPassenger,key()));UUID acceptedId=UUID.fromString(accepted.path("id").asText()),payment=UUID.fromString(accepted.path("paymentId").asText());ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",false),acceptedPassenger,key()));var unrelated=avulsa();var actor=admin();var result=suspend(actor,driver);assertEquals(0,result.path("affectedParcels").asInt());assertTrue(result.path("financialCaseId").isNull());assertEquals("cancelled",state("ride_requests",pending));assertEquals("cancelled",state("ride_requests",acceptedId));assertEquals("expired",state("payments",payment));for(UUID request:List.of(pending,acceptedId)) assertEquals(2,count("SELECT count(*) FROM notifications WHERE ref_id=? AND title='Solicitação encerrada'",request));assertEquals("confirmed",state("ride_reservations",unrelated.reservation()));assertEquals(0,count("SELECT count(*) FROM occurrences WHERE reporter_id=?",actor.id()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void existingBlockedCasesAndBoardedMonthlyLegsAreNotDuplicatedBySuspension() throws Exception {
        var monthly=paid();var boarded=leg(monthly,0);boarded(boarded);var contested=leg(monthly,1);ok(send("POST",reservation(contested,"contest"),Map.of("reason","Synthetic prior monthly case"),monthly.passenger(),key()));var before=parcel(contested.parcel());var actor=admin();var result=suspend(actor,monthly.driver());assertEquals(monthly.legs()-2,result.path("affectedParcels").asInt());UUID report=UUID.fromString(result.path("financialCaseId").asText());assertEquals(0,count("SELECT count(*) FROM occurrence_parcels WHERE occurrence_id=? AND parcel_id IN (?,?)",report,boarded.parcel(),contested.parcel()));assertEquals(before,parcel(contested.parcel()));assertEquals("boarded",state("ride_reservations",boarded.reservation()));assertEquals("future",state("parcels",boarded.parcel()));assertEquals(boarded.cents(),ledger.balance(monthly.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void concurrentDifferentReviewKeysHaveOneWinningAccountVersion() throws Exception {
        var actor=admin();var target=avulsa();try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var gate=new CountDownLatch(1);var a=executor.submit(() -> { gate.await();return send("POST",review(target.driver()),decision("suspended","active"),actor,key()); });var b=executor.submit(() -> { gate.await();return send("POST",review(target.driver()),decision("suspended","active"),actor,key()); });gate.countDown();assertEquals(List.of(200,409),List.of(a.get(15,TimeUnit.SECONDS).statusCode(),b.get(15,TimeUnit.SECONDS).statusCode()).stream().sorted().toList()); }assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='account_reviewed' AND resource_id=?",target.driver().id()));assertEquals(1,count("SELECT count(*) FROM occurrences WHERE reporter_id=? AND other_id=?",actor.id(),target.driver().id()));assertEquals(target.cents(),ledger.balance(target.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void sameReviewKeyReplaysExactlyWithoutBlockingOrAuditingTwice() throws Exception {
        var monthly=paid();var actor=admin();String key=key();var body=decision("suspended","active");JsonNode result;try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var a=executor.submit(() -> send("POST",review(monthly.driver()),body,actor,key));var b=executor.submit(() -> send("POST",review(monthly.driver()),body,actor,key));result=ok(a.get(15,TimeUnit.SECONDS));assertEquals(result,ok(b.get(15,TimeUnit.SECONDS))); }assertEquals(result,ok(send("POST",review(monthly.driver()),body,actor,key)));code(send("POST",review(monthly.driver()),decision("active","suspended"),actor,key),409,"IDEMPOTENCY_CONFLICT");assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='account_reviewed' AND resource_id=?",monthly.driver().id()));assertEquals(monthly.total(),ledger.balance(monthly.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void lateAuditFailureRestoresAccountRidesContractsMoneyCasesNotificationsAndKey() throws Exception {
        var monthly=paid();var avulsa=avulsa(monthly.driver(),monthly.passenger(),offer(monthly.driver(),true));var actor=admin();String key=key();int notices=count("SELECT count(*) FROM notifications WHERE user_id IN (?,?)",monthly.driver().id(),monthly.passenger().id());sql.execute("CREATE FUNCTION synthetic_account_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='account_reviewed' THEN RAISE EXCEPTION 'Synthetic account failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");sql.execute("CREATE TRIGGER synthetic_account_audit_failure BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION synthetic_account_audit_failure()");try { code(send("POST",review(monthly.driver()),decision("suspended","active"),actor,key),409,"INVARIANT_VIOLATION"); }finally { sql.execute("DROP TRIGGER synthetic_account_audit_failure ON audit_logs");sql.execute("DROP FUNCTION synthetic_account_audit_failure()"); }assertEquals("active",account(monthly.driver()));assertEquals("active",state("subscriptions",monthly.contract()));assertEquals("active",state("routines",monthly.routine()));assertEquals("planned",state("rides",avulsa.ride()));assertEquals("confirmed",state("ride_reservations",avulsa.reservation()));assertEquals("future",state("parcels",avulsa.parcel()));assertEquals(monthly.total()+avulsa.cents(),ledger.balance(monthly.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(monthly.passenger().id(),AccountKind.BLOCKED));assertEquals(0,count("SELECT count(*) FROM occurrences WHERE reporter_id=?",actor.id()));assertEquals(notices,count("SELECT count(*) FROM notifications WHERE user_id IN (?,?)",monthly.driver().id(),monthly.passenger().id()));assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",actor.id(),key));ok(send("POST",review(monthly.driver()),decision("suspended","active"),actor,key));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void accountReviewAndRestorationReplayAcrossInstancesAndDirectoryResponsesMatch() throws Exception {
        var actor=admin();try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { for(boolean peerFirst:List.of(true,false)) { var monthly=paid();var future=avulsa(monthly.driver(),monthly.passenger(),offer(monthly.driver(),true));for(var body:List.of(decision("suspended","active"),decision("active","suspended"))) { String key=key();if(peerFirst) { var old=peer.request("POST",review(monthly.driver()),body,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(old.path("body"),ok(send("POST",review(monthly.driver()),body,actor,key))); }else { var saved=ok(send("POST",review(monthly.driver()),body,actor,key));var old=peer.request("POST",review(monthly.driver()),body,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(saved,old.path("body")); } }assertEquals("blocked",state("parcels",future.parcel()));assertEquals("interrupted",state("subscriptions",monthly.contract()));assertEquals(peer.request("GET","/api/v1/admin/accounts?limit=4&offset=1",null,headers(actor,null)).path("body"),ok(send("GET","/api/v1/admin/accounts?limit=4&offset=1",null,actor,null))); } }assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void uppercaseTargetUuidPreservesCanonicalLookupAndRetainsTheRawReplayIdentity() throws Exception {
        var actor=admin();var target=user(false);String path=review(target).replace(target.id().toString(),target.id().toString().toUpperCase(Locale.ROOT)),key=key();var body=decision("suspended","active");try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { var saved=ok(send("POST",path,body,actor,key));assertEquals(target.id().toString().toUpperCase(Locale.ROOT),saved.path("id").asText());assertEquals(saved,peer.request("POST",path,body,headers(actor,key)).path("body")); }
    }
    @Test void reviewRetriesTheEntireTransactionWhenANewParticipantCommitsBeforeTheTargetLock() throws Exception {
        var first=avulsa();var actor=admin();var newcomer=user(false);String key=key();var pending=new java.util.concurrent.atomic.AtomicReference<Future<HttpResponse<String>>>();var created=new java.util.concurrent.atomic.AtomicReference<com.unicarona.reservation.dto.RequestDto.Created>();var marker=new java.util.concurrent.atomic.AtomicReference<java.sql.Timestamp>();try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tx.executeWithoutResult(ignored -> {
                sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",first.driver().id());pending.set(executor.submit(() -> send("POST",review(first.driver()),decision("suspended","active"),actor,key)));boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<deadline) { sql.execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FROM users u%' AND query LIKE '%FOR UPDATE OF u%')",Boolean.class))) { waiting=true;break; }try { Thread.sleep(10); }catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); } }assertTrue(waiting,"Account review must reach the ordered participant lock");var passenger=new com.unicarona.security.Actor(newcomer.id(),"user","active",null,null,null,true,Set.of("ROLE_PASSENGER"));created.set(app.getBean(com.unicarona.reservation.service.RequestTransactions.class).create(passenger,new com.unicarona.reservation.dto.RequestDto.Avulsa("avulsa",first.ride().toString()),key()));marker.set(sql.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class));
            });ok(pending.get().get(15,TimeUnit.SECONDS));
        }assertTrue(sql.queryForObject("SELECT created_at>? FROM idempotency_keys WHERE actor_id=? AND key=? AND scope='admin:account:review'",Boolean.class,marker.get(),actor.id(),key));assertEquals("cancelled",state("ride_requests",created.get().id()));assertEquals("expired",state("payments",created.get().paymentId()));assertEquals(1,count("SELECT count(*) FROM notifications WHERE user_id=? AND ref_id=? AND title='Solicitação encerrada'",newcomer.id(),created.get().id()));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='account_reviewed' AND resource_id=?",first.driver().id()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void restorationCreatesNoFinancialCaseAndKeepsEveryExistingBlock() throws Exception {
        var future=avulsa();var actor=admin();var suspended=suspend(actor,future.driver());UUID caseId=UUID.fromString(suspended.path("financialCaseId").asText());var before=parcel(future.parcel());restore(actor,future.driver());assertEquals(before,parcel(future.parcel()));assertEquals("financial_pending",state("occurrences",caseId));assertEquals("cancelled",state("rides",future.ride()));assertEquals(1,count("SELECT count(*) FROM occurrences WHERE reporter_id=? AND other_id=?",actor.id(),future.driver().id()));assertEquals(future.cents(),ledger.balance(future.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void swaggerDocumentsPrivateDirectoryStatusVersionAndRequiredMutationHeaders() throws Exception {
        var docs=ok(send("GET","/api/docs/json",null,null,null));var operation=docs.path("paths").path("/api/v1/admin/accounts/{id}/review").path("post");assertTrue(operation.path("requestBody").path("required").asBoolean());assertFalse(operation.path("security").isEmpty());var required=new HashSet<String>();operation.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) required.add(p.path("name").asText()); });assertTrue(required.containsAll(Set.of("id","Origin","x-csrf-token","idempotency-key")));var schema=docs.path("components").path("schemas").path("AdminAccountReviewInput");assertTrue(schema.path("required").toString().contains("expectedStatus"));assertFalse(schema.path("properties").has("role"));
    }
}
