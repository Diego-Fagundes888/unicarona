package com.unicarona.job;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.job.service.*;
import com.unicarona.job.repository.MaintenanceRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import static org.junit.jupiter.api.Assertions.*;

/** Uses actual paid reservations, real HTTP and PostgreSQL financial/state guards. */
class MaintenanceHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static LedgerService ledger;
    private static TransactionTemplate tx;
    private static MaintenanceService maintenance;
    private static MutableMaintenanceClock calendar;
    @TestConfiguration(proxyBeanMethods=false)
    static class CalendarConfiguration {
        @Bean @Primary MutableMaintenanceClock controlledMonthlyMaintenanceClock() { return new MutableMaintenanceClock(); }
    }
    private static UUID university,course,classId,otherClass;
    private static String server,passwordHash,start;
    private static final String PASSWORD="Synthetic contract HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    private record Paid(Session driver,Session passenger,UUID routine,UUID payment,UUID contract,int total,int legs) {}
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,CalendarConfiguration.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        calendar=app.getBean(MutableMaintenanceClock.class);
        ledger=app.getBean(LedgerService.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));maintenance=app.getBean(MaintenanceService.class);
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1).withDayOfMonth(1).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('maintenance-university','Contract University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'maintenance-course','Contract Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void settings() { sql.update("DELETE FROM rate_limits");sql.update("UPDATE subscription_schedule sc SET held=false FROM subscriptions s WHERE sc.subscription_id=s.id AND s.end_date<CURRENT_DATE");sql.update("UPDATE subscriptions SET status='ended',renew=false WHERE end_date<CURRENT_DATE AND status IN ('active','grace','renew_conflict','cancelled_renewal')");sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    @BeforeEach void resetMonthlyClock() { calendar.set(Instant.now()); }
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
    private record Unpaid(Session driver,Session passenger,UUID request,UUID payment) {}
    private Unpaid unpaid(boolean automatic) throws Exception {
        var driver=user(true);var passenger=user(false);var ride=offer(driver,automatic);UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",ride),passenger,key())).path("id").asText());UUID payment=sql.query("SELECT id FROM payments WHERE request_id=?",(r,n) -> r.getObject(1,UUID.class),request).stream().findFirst().orElse(null);return new Unpaid(driver,passenger,request,payment);
    }
    private UUID renewalRoutine(Session driver,int capacity) throws Exception {
        return UUID.fromString(ok(send("POST","/api/v1/routines",Map.of("region","Gama","meeting","Synthetic renewal stop","direction","ambos","time","23:58","returnTime","23:59","weekdays",List.of(0,1,2,3,4,5,6),"capacity",capacity,"distance",10,"automatic",true),driver,key())).path("id").asText());
    }
    private UUID expired(Session driver,Session passenger,UUID routine,int daysAgo,boolean renew) {
        return sql.queryForObject("INSERT INTO subscriptions(routine_id,driver_id,passenger_id,class_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status,renew) SELECT ?,?,?,s.class_id,(now() AT TIME ZONE un.time_zone)::date-?-1,(now() AT TIME ZONE un.time_zone)::date-?,ARRAY[0,1,2,3,4,5,6]::smallint[],'ambos',1000,1000,'active',? FROM student_profiles s JOIN universities un ON un.id=s.university_id WHERE s.user_id=? RETURNING id",UUID.class,routine,driver.id(),passenger.id(),daysAgo,daysAgo,renew,passenger.id());
    }
    private int held(UUID contract) { return count("SELECT count(*) FROM subscription_schedule WHERE subscription_id=? AND held",contract); }
    private void deadline(Leg leg) { sql.update("UPDATE ride_reservations SET confirmation_deadline=now()-interval '1 second' WHERE id=?",leg.reservation()); }
    @Test void requestsExpireByRequestOrPaymentDeadlineInBoundedBatchesWithEssentialNotices() throws Exception {
        var pending=unpaid(false);var accepted=unpaid(true);sql.update("UPDATE ride_requests SET expires_at=now()-interval '2 minutes' WHERE id=?",pending.request());sql.update("UPDATE payments SET expires_at=now()-interval '1 second' WHERE id=?",accepted.payment());sql.update("UPDATE users SET account_status='suspended' WHERE id IN (?,?)",accepted.passenger().id(),accepted.driver().id());sql.update("UPDATE student_profiles SET notices=false WHERE user_id IN (?,?)",pending.passenger().id(),accepted.passenger().id());
        assertEquals(new MaintenanceService.Counts(1,0,0),maintenance.run(1));assertEquals("expired",state("ride_requests",pending.request()));assertEquals("accepted",state("ride_requests",accepted.request()));assertEquals(new MaintenanceService.Counts(1,0,0),maintenance.run(1));assertEquals("expired",state("payments",accepted.payment()));assertEquals("expired",state("ride_requests",accepted.request()));assertEquals(new MaintenanceService.Counts(0,0,0),maintenance.run());
        for(var fixture:List.of(pending,accepted)) { assertEquals(1,count("SELECT count(*) FROM notifications WHERE ref_id=? AND title='Pedido expirado'",fixture.request()));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_expired' AND actor_id IS NULL",fixture.request()));assertEquals("deadline",sql.queryForObject("SELECT reason FROM ride_requests WHERE id=?",String.class,fixture.request())); }assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void staleRequestCandidateRechecksAfterCancellationAndNeverExpiresConfirmedPayment() throws Exception {
        var fixture=unpaid(true);var candidate=new MaintenanceRepository.Request(fixture.request(),fixture.passenger().id());ok(send("POST","/api/v1/ride-requests/"+fixture.request()+"/cancel",Map.of(),fixture.passenger(),key()));assertFalse(app.getBean(MaintenanceTransactions.class).request(candidate));assertEquals(0,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_expired'",fixture.request()));var leg=avulsa();sql.update("UPDATE payments SET expires_at=now()-interval '1 second' WHERE id=?",leg.payment());assertEquals(0,maintenance.run().requests());assertEquals("approved",state("payments",leg.payment()));assertEquals("future",state("parcels",leg.parcel()));
    }
    @Test void finalTimeoutReleasesOnceWithFrozenCommissionEvenWhenAccountsAreRestricted() throws Exception {
        var leg=avulsa();awaiting(leg);deadline(leg);long commission=ledger.balance(null,AccountKind.COMMISSION);sql.update("UPDATE users SET account_status='suspended' WHERE id IN (?,?)",leg.passenger().id(),leg.driver().id());assertEquals(1,maintenance.run().finals());assertEquals(0,maintenance.run().finals());assertEquals("completed",state("ride_reservations",leg.reservation()));assertEquals("timeout",sql.queryForObject("SELECT final_response FROM ride_reservations WHERE id=?",String.class,leg.reservation()));assertEquals("released",state("parcels",leg.parcel()));int fee=(leg.cents()*1000+5000)/10000;assertEquals(leg.cents()-fee,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(commission+fee,ledger.balance(null,AccountKind.COMMISSION));assertEquals(0,ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='final_confirmation_timeout' AND actor_id IS NULL",leg.reservation()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void noClassTimeoutReturnsFullCreditAndPreservesOtherPaidLegs() throws Exception {
        var paid=paid();var leg=leg(paid,0);long retained=ledger.balance(null,AccountKind.RETAINED),commission=ledger.balance(null,AccountKind.COMMISSION);ok(send("POST",ride(leg,"no-class"),Map.of("reason","Synthetic timeout"),leg.driver(),key()));deadline(leg);assertEquals(1,maintenance.run().finals());assertEquals("credited",state("parcels",leg.parcel()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(retained,ledger.balance(null,AccountKind.RETAINED));assertEquals(commission,ledger.balance(null,AccountKind.COMMISSION));assertEquals(paid.total()-leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals(paid.legs()-1,count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='future'",paid.payment()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void contestAndFutureDeadlineNeverAutoReleaseBlockedMoney() throws Exception {
        var leg=avulsa();awaiting(leg);assertEquals(0,maintenance.run().finals());ok(send("POST",reservation(leg,"contest"),Map.of("reason","Synthetic contested deadline"),leg.passenger(),key()));deadline(leg);assertEquals(0,maintenance.run().finals());assertEquals("blocked",state("parcels",leg.parcel()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void concurrentMaintenanceWorkersSettleAndAuditSameParcelExactlyOnce() throws Exception {
        var leg=avulsa();awaiting(leg);deadline(leg);try(var executor=Executors.newFixedThreadPool(2)) { var startGate=new CountDownLatch(1);var a=executor.submit(() -> { startGate.await();return maintenance.run(); });var b=executor.submit(() -> { startGate.await();return maintenance.run(); });startGate.countDown();assertEquals(1,a.get(10,TimeUnit.SECONDS).finals()+b.get(10,TimeUnit.SECONDS).finals()); }assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='final_confirmation_timeout'",leg.reservation()));assertEquals("released",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void fullGraceAgendaHoldsSeatsWithoutBoardingOrMoneyAndPaymentRenewsAtomically() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);int journals=count("SELECT count(*) FROM ledger_transactions");assertEquals(1,maintenance.run().subscriptions());assertEquals("grace",state("subscriptions",old));assertTrue(held(old)>0);assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE subscription_id=?",old));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions"));assertTrue(sql.queryForObject("SELECT grace_until=((end_date+1)::timestamp AT TIME ZONE 'America/Sao_Paulo')+interval '72 hours' FROM subscriptions WHERE id=?",Boolean.class,old));
        UUID heldRide=sql.queryForObject("SELECT ride_id FROM subscription_schedule WHERE subscription_id=? AND held ORDER BY ride_id LIMIT 1",UUID.class,old);code(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",heldRide),user(false),key()),409,"RIDE_FULL");String current=sql.queryForObject("SELECT (now() AT TIME ZONE 'America/Sao_Paulo')::date::text",String.class);var input=Map.of("type","mensal","routineId",routine,"startDate",current,"directions","ambos","weekdays",List.of(0,1,2,3,4,5,6),"previousContractId",old);UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",input,passenger,key())).path("id").asText());UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),driver,key())).path("paymentId").asText());int cents=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);fund(passenger,cents);assertTrue(ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",true),passenger,key())).path("confirmed").asBoolean());assertEquals("renewed",state("subscriptions",old));assertEquals(0,held(old));assertTrue(count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",payment)>0);assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void concurrentRenewalWorkersRetainAgendaAndNotifyOnlyOnce() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);try(var executor=Executors.newFixedThreadPool(2)) { var a=executor.submit(() -> { return maintenance.run(); });var b=executor.submit(() -> { return maintenance.run(); });assertEquals(1,a.get(10,TimeUnit.SECONDS).subscriptions()+b.get(10,TimeUnit.SECONDS).subscriptions()); }assertEquals("grace",state("subscriptions",old));assertTrue(held(old)>0);assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='subscription_grace'",old));assertEquals(1,count("SELECT count(*) FROM notifications WHERE ref_id=? AND title='Carência de renovação'",old));
    }
    @Test void unavailableRoutineConflictsWithoutLosingDeadlineOrCreatingPaidReservations() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);sql.update("UPDATE routines SET status='paused' WHERE id=?",routine);assertEquals(1,maintenance.run().subscriptions());assertEquals("renew_conflict",state("subscriptions",old));assertEquals(0,held(old));assertTrue(sql.queryForObject("SELECT grace_until>now() FROM subscriptions WHERE id=?",Boolean.class,old));assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE subscription_id=?",old));assertEquals("false",sql.queryForObject("SELECT metadata->>'held' FROM audit_logs WHERE action='subscription_grace' AND resource_id=?",String.class,old));
    }
    @Test void missingAcceptanceOfNewTermsCommitsRenewalConflictWithoutHoldsOrFinancialWrites() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);
        var baseline=app.getBean(com.unicarona.privacy.service.LegalDocumentTemplates.class).prepare();var peer=baseline.documents().stream().filter(d->d.slug().equals("terms")).findFirst().orElseThrow();
        var body=json.readTree(peer.content());var canonical=new TreeMap<String,String>();canonical.put("title",peer.title());canonical.put("body",body.path("body").asText()+"\n\nSynthetic renewal change "+UUID.randomUUID());String content=app.getBean(ObjectMapper.class).writeValueAsString(canonical);
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var changed=new com.unicarona.privacy.service.LegalDocumentTemplates.Prepared("terms",2,peer.title(),peer.kind(),peer.scopes(),true,"Synthetic renewal change",content,digest,"1.2."+digest.substring(0,16));
        var docs=new ArrayList<>(baseline.documents());docs.removeIf(d->d.slug().equals("terms"));docs.add(changed);
        app.getBean(com.unicarona.privacy.service.LegalPublicationService.class).publish(new com.unicarona.privacy.service.LegalDocumentTemplates.Bundle("published",docs));
        try {
            int before=count("SELECT count(*) FROM ledger_transactions");
            assertEquals(1,maintenance.run().subscriptions());assertEquals("renew_conflict",state("subscriptions",old));assertEquals(0,held(old));
            assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE subscription_id=?",old));assertEquals(before,count("SELECT count(*) FROM ledger_transactions"));
            assertEquals(1,count("SELECT count(*) FROM notifications WHERE ref_id=? AND title='Renovação exige revisão'",old));
            assertEquals(0,count("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND document_slug='terms' AND document_version=?",passenger.id(),changed.version()));
        } finally {sql.update("UPDATE legal_document_heads SET version=? WHERE slug='terms'",peer.version());}
    }
    @Test void businessFailureAfterMaterializationRollsBackSavepointButCommitsGraceConflict() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1);UUID missing=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES(?,'Synthetic no current price','Gama','DF') RETURNING id",UUID.class,"missing-price-"+UUID.randomUUID());UUID newCourse=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'missing','Synthetic') RETURNING id",UUID.class,missing);UUID newClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,missing,newCourse);sql.update("UPDATE student_profiles SET university_id=?,course_id=?,class_id=? WHERE user_id IN (?,?)",missing,newCourse,newClass,driver.id(),passenger.id());sql.update("UPDATE routines SET university_id=?,class_id=? WHERE id=?",missing,newClass,routine);UUID old=expired(driver,passenger,routine,1,true);sql.update("DELETE FROM rides WHERE routine_id=?",routine);int before=count("SELECT count(*) FROM rides WHERE routine_id=?",routine);assertEquals(0,before);assertEquals(1,maintenance.run().subscriptions());assertEquals("renew_conflict",state("subscriptions",old));assertEquals(before,count("SELECT count(*) FROM rides WHERE routine_id=?",routine));assertTrue(sql.queryForObject("SELECT grace_until>now() FROM subscriptions WHERE id=?",Boolean.class,old));assertEquals(1,count("SELECT count(*) FROM notifications WHERE ref_id=? AND title='Renovação exige revisão'",old));assertEquals(0,held(old));
    }
    @Test void oneConflictingRidePreventsPartialHoldsAndKeepsExistingPaidPassenger() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);var schedule=app.getBean(com.unicarona.subscription.repository.ScheduleRepository.class);String current=sql.queryForObject("SELECT (now() AT TIME ZONE 'America/Sao_Paulo')::date::text",String.class);tx.executeWithoutResult(status -> {var dates=schedule.dates("America/Sao_Paulo",current);schedule.materialize(routine,current,dates.end());});UUID ride=sql.queryForObject("SELECT r.id FROM rides r WHERE routine_id=? AND (ride_date+departure_time) AT TIME ZONE 'America/Sao_Paulo'>now() ORDER BY ride_date,departure_time LIMIT 1",UUID.class,routine);var paid=avulsa(driver,user(false),ride);assertEquals(1,maintenance.run().subscriptions());assertEquals("renew_conflict",state("subscriptions",old));assertEquals(0,held(old));assertEquals("confirmed",state("ride_reservations",paid.reservation()));assertEquals("future",state("parcels",paid.parcel()));assertEquals(paid.cents(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void expiredGraceOrDisabledRenewalReleasesOnlyUnpaidHoldsAndKeepsPaidHistory() throws Exception {
        var paid=paid();var passenger=paid.passenger();UUID routine=renewalRoutine(paid.driver(),1),old=expired(paid.driver(),passenger,routine,1,true);assertEquals(1,maintenance.run().subscriptions());assertTrue(held(old)>0);sql.update("UPDATE subscriptions SET grace_until=now()-interval '1 second' WHERE id=?",old);assertEquals(1,maintenance.run().subscriptions());assertEquals("expired",state("subscriptions",old));assertEquals(0,held(old));assertEquals(paid.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='future'",paid.payment()));assertEquals(paid.total(),ledger.balance(passenger.id(),AccountKind.FUTURE));UUID noRenew=expired(paid.driver(),user(false),routine,1,false);assertEquals(1,maintenance.run().subscriptions());assertEquals("ended",state("subscriptions",noRenew));assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,noRenew));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void earlierItemStaysCommittedWhenLaterTransactionFailsAndCanRetry() throws Exception {
        var first=unpaid(true);var second=unpaid(true);sql.update("UPDATE ride_requests SET expires_at=now()-interval '2 minutes' WHERE id=?",first.request());sql.update("UPDATE ride_requests SET expires_at=now()-interval '1 minute' WHERE id=?",second.request());sql.execute("CREATE FUNCTION synthetic_maintenance_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ref_id='"+second.request()+"'::uuid THEN RAISE EXCEPTION 'Synthetic failure' USING ERRCODE='23514';END IF;RETURN NEW;END $$");sql.execute("CREATE TRIGGER synthetic_maintenance_failure BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION synthetic_maintenance_failure()");
        try { assertThrows(RuntimeException.class,maintenance::run);assertEquals("expired",state("ride_requests",first.request()));assertEquals("expired",state("payments",first.payment()));assertEquals("accepted",state("ride_requests",second.request()));assertEquals("awaiting",state("payments",second.payment()));assertEquals(0,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='request_expired'",second.request())); }finally { sql.execute("DROP TRIGGER synthetic_maintenance_failure ON notifications");sql.execute("DROP FUNCTION synthetic_maintenance_failure()"); }assertEquals(1,maintenance.run().requests());assertEquals("expired",state("payments",second.payment()));
    }
    @Test void ambientTransactionIsRejectedAndPruningPreservesUnexpiredLimits() {
        sql.update("INSERT INTO rate_limits(bucket,attempts,expires_at) VALUES('synthetic-expired',1,now()-interval '1 second'),('synthetic-live',1,now()+interval '1 hour')");assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,() -> tx.executeWithoutResult(status -> maintenance.run()));assertEquals(2,count("SELECT count(*) FROM rate_limits WHERE bucket LIKE 'synthetic-%'"));maintenance.run();assertEquals(0,count("SELECT count(*) FROM rate_limits WHERE bucket='synthetic-expired'"));assertEquals(1,count("SELECT count(*) FROM rate_limits WHERE bucket='synthetic-live'"));
    }
    @Test void independentJavaInstancesMaintenanceAgreeOnRequestTimeoutFinalAndGraceStates() throws Exception {
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var a=unpaid(true);sql.update("UPDATE payments SET expires_at=now()-interval '1 second' WHERE id=?",a.payment());var old=peer.maintenance(100);assertEquals(200,old.path("status").asInt());var b=unpaid(true);sql.update("UPDATE payments SET expires_at=now()-interval '1 second' WHERE id=?",b.payment());assertEquals(old.path("body"),json.valueToTree(maintenance.run()));assertEquals(state("ride_requests",a.request()),state("ride_requests",b.request()));
            var oldFinal=avulsa();awaiting(oldFinal);deadline(oldFinal);var oldCounts=peer.maintenance(100);var javaFinal=avulsa();awaiting(javaFinal);deadline(javaFinal);assertEquals(oldCounts.path("body"),json.valueToTree(maintenance.run()));assertEquals(state("parcels",oldFinal.parcel()),state("parcels",javaFinal.parcel()));assertEquals(ledger.balance(oldFinal.driver().id(),AccountKind.DRIVER_AVAILABLE),ledger.balance(javaFinal.driver().id(),AccountKind.DRIVER_AVAILABLE));
            var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),oldContract=expired(driver,passenger,routine,1,true);var oldGrace=peer.maintenance(100);var javaDriver=user(true);UUID javaContract=expired(javaDriver,user(false),renewalRoutine(javaDriver,1),1,true);
            assertEquals(oldGrace.path("body"),json.valueToTree(maintenance.run()));assertEquals(state("subscriptions",oldContract),state("subscriptions",javaContract));assertEquals(held(oldContract),held(javaContract));assertEquals(0,ledger.reconciliation().difference());
        }
    }
    @Test void databaseFailureDuringMaterializationRollsBackEntireContractAndCanRetry() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);sql.update("DELETE FROM rides WHERE routine_id=?",routine);
        sql.execute("CREATE FUNCTION synthetic_renewal_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.routine_id='"+routine+"'::uuid THEN RAISE EXCEPTION 'Synthetic database failure' USING ERRCODE='23514';END IF;RETURN NEW;END $$");sql.execute("CREATE TRIGGER synthetic_renewal_failure BEFORE INSERT ON rides FOR EACH ROW EXECUTE FUNCTION synthetic_renewal_failure()");
        try { assertThrows(RuntimeException.class,maintenance::run);assertEquals("active",state("subscriptions",old));assertTrue(sql.queryForObject("SELECT grace_until IS NULL FROM subscriptions WHERE id=?",Boolean.class,old));assertEquals(0,count("SELECT count(*) FROM rides WHERE routine_id=?",routine));assertEquals(0,count("SELECT count(*) FROM notifications WHERE ref_id=?",old));assertEquals(0,count("SELECT count(*) FROM audit_logs WHERE action='subscription_grace' AND resource_id=?",old)); }
        finally { sql.execute("DROP TRIGGER synthetic_renewal_failure ON rides");sql.execute("DROP FUNCTION synthetic_renewal_failure()"); }
        assertEquals(1,maintenance.run().subscriptions());assertEquals("grace",state("subscriptions",old));assertTrue(held(old)>0);
    }
    @Test void graceUsesCampusMidnightAcrossJvmAndDatabaseDateBoundaries() throws Exception {
        TimeZone peer=TimeZone.getDefault();sql.update("UPDATE universities SET time_zone='Asia/Tokyo' WHERE id=?",university);TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        try {
            var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);LocalDate end=LocalDate.parse(sql.queryForObject("SELECT end_date::text FROM subscriptions WHERE id=?",String.class,old));Instant expected=end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().plus(Duration.ofHours(72));assertEquals(1,maintenance.run().subscriptions());assertEquals("grace",state("subscriptions",old));Instant actual=sql.queryForObject("SELECT grace_until FROM subscriptions WHERE id=?",(r,n) -> r.getTimestamp(1).toInstant(),old);assertEquals(expected,actual);assertNotEquals(end.plusDays(1).atStartOfDay(ZoneId.of("America/Los_Angeles")).toInstant().plus(Duration.ofHours(72)),actual);
        }finally { TimeZone.setDefault(peer);sql.update("UPDATE universities SET time_zone='America/Sao_Paulo' WHERE id=?",university); }
    }
    @Test void proposedDeparturePreventsUnpaidRenewalFromHoldingAnyPartOfAgenda() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=renewalRoutine(driver,1),old=expired(driver,passenger,routine,1,true);UUID ride=sql.queryForObject("SELECT id FROM rides WHERE routine_id=? AND (ride_date+departure_time) AT TIME ZONE 'America/Sao_Paulo'>now() ORDER BY ride_date,departure_time LIMIT 1",UUID.class,routine);sql.update("UPDATE rides SET proposed_time='23:57',proposal_reason='Synthetic proposal' WHERE id=?",ride);assertEquals(1,maintenance.run().subscriptions());assertEquals("renew_conflict",state("subscriptions",old));assertEquals(0,held(old));assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE subscription_id=?",old));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void paidContractCrossesLocalMonthAndExactGraceDeadlineWithoutEditingFinancialDates() throws Exception {
        var paid=paid();var repository=app.getBean(MaintenanceRepository.class);var work=app.getBean(MaintenanceTransactions.class);
        var next=LocalDate.parse(start).plusMonths(1).withDayOfMonth(1);
        var boundary=next.atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant();
        int journals=count("SELECT count(*) FROM ledger_transactions");
        calendar.set(boundary.minusMillis(1));
        assertFalse(repository.contracts(10000).contains(paid.contract()));assertFalse(work.subscription(paid.contract()));
        assertEquals("active",state("subscriptions",paid.contract()));
        calendar.set(boundary);assertTrue(repository.contracts(10000).contains(paid.contract()));
        assertTrue(work.subscription(paid.contract()));assertEquals("grace",state("subscriptions",paid.contract()));
        assertTrue(held(paid.contract())>0);assertEquals(paid.legs(),count("SELECT count(*) FROM ride_reservations WHERE subscription_id=?",paid.contract()));
        assertEquals(journals,count("SELECT count(*) FROM ledger_transactions"));
        var deadline=sql.queryForObject("SELECT grace_until FROM subscriptions WHERE id=?",(r,n) -> r.getTimestamp(1).toInstant(),paid.contract());
        assertEquals(boundary.plus(Duration.ofHours(72)),deadline);
        calendar.set(deadline.minusMillis(1));assertFalse(repository.contracts(10000).contains(paid.contract()));assertFalse(work.subscription(paid.contract()));
        calendar.set(deadline);assertTrue(repository.contracts(10000).contains(paid.contract()));assertTrue(work.subscription(paid.contract()));
        assertEquals("expired",state("subscriptions",paid.contract()));assertEquals(0,held(paid.contract()));assertFalse(work.subscription(paid.contract()));
        assertEquals(paid.legs(),count("SELECT count(*) FROM ride_reservations WHERE subscription_id=?",paid.contract()));
        assertEquals(journals,count("SELECT count(*) FROM ledger_transactions"));assertEquals(0,ledger.reconciliation().difference());
        code(send("POST","/api/v1/ride-requests",Map.of("type","mensal","routineId",paid.routine(),"startDate",next.toString(),"directions","ambos","weekdays",List.of(1,3,5),"previousContractId",paid.contract()),paid.passenger(),key()),409,"RENEWAL_UNAVAILABLE");
    }
    @Test void paidContractRenewsInsideControlledGraceWithoutSpendingOrDuplicatingHolds() throws Exception {
        var paid=paid();var work=app.getBean(MaintenanceTransactions.class);var next=LocalDate.parse(start).plusMonths(1).withDayOfMonth(1);
        calendar.set(next.atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant().plusSeconds(1));
        assertTrue(work.subscription(paid.contract()));assertEquals("grace",state("subscriptions",paid.contract()));int seats=held(paid.contract());assertTrue(seats>0);
        var input=Map.of("type","mensal","routineId",paid.routine(),"startDate",next.toString(),"directions","ambos","weekdays",List.of(1,3,5),"previousContractId",paid.contract());
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",input,paid.passenger(),key())).path("id").asText());
        UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),paid.driver(),key())).path("paymentId").asText());
        int cents=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);fund(paid.passenger(),cents);
        assertTrue(ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",true),paid.passenger(),key())).path("confirmed").asBoolean());
        assertEquals("renewed",state("subscriptions",paid.contract()));assertEquals(0,held(paid.contract()));
        assertEquals(seats,count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",payment));
        assertFalse(work.subscription(paid.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
}


