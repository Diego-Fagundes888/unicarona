package com.unicarona.reservation;

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
class LifecycleHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static LedgerService ledger;
    private static TransactionTemplate tx;
    private static UUID university,course,classId,otherClass;
    private static String server,passwordHash,start;
    private static java.nio.file.Path privacyJournal;
    private static final String PASSWORD="Synthetic contract HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    private record Paid(Session driver,Session passenger,UUID routine,UUID payment,UUID contract,int total,int legs) {}
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        privacyJournal=java.nio.file.Files.createTempDirectory("unicarona-lifecycle-privacy-");var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.add("--PRIVACY_ERASURE_JOURNAL_PATH="+privacyJournal);
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));
        ledger=app.getBean(LedgerService.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1).withDayOfMonth(1).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('lifecycle-university','Contract University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'lifecycle-course','Contract Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
        otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void settings() { sql.update("DELETE FROM rate_limits");sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000)",university); }
    @AfterAll static void close() throws Exception { if(app!=null) app.close();if(database!=null) database.close();if(privacyJournal!=null){assertTrue(privacyJournal.getFileName().toString().startsWith("unicarona-lifecycle-privacy-"));try(var files=java.nio.file.Files.list(privacyJournal)){for(var file:files.toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(privacyJournal);}}
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
    private void today(Leg leg) { RideFixtureClock.today(sql,leg.ride()); }
    private String pin(Leg leg) throws Exception { String pin=detail(leg).path("pin").asText();assertTrue(pin.matches("[0-9]{4}"));return pin; }
    private void begin(Leg leg) throws Exception { today(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key())); }
    private void boarded(Leg leg) throws Exception { today(leg);ok(send("POST",reservation(leg,"board"),Map.of("pin",pin(leg)),leg.driver(),key())); }
    private void awaiting(Leg leg) throws Exception { boarded(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key())); }
    private JsonNode requestClosure(Session user)throws Exception {
        String version=ok(send("GET","/api/v1/privacy/closure",null,user,null)).path("policyVersion").asText();
        var response=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),user,key());assertEquals(202,response.statusCode(),response.body());return tree(response);
    }
    @Test void accountClosureKeepsAnExistingRideOperableAndDoesNotAcceptNewBookings()throws Exception {
        var leg=avulsa();today(leg);long before=ledger.balance(leg.passenger().id(),AccountKind.FUTURE);
        assertEquals("pending_obligations",requestClosure(leg.passenger()).path("status").asText());assertEquals("pending_obligations",requestClosure(leg.driver()).path("status").asText());
        assertEquals(before,ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals("confirmed",state("ride_reservations",leg.reservation()));assertEquals("approved",state("payments",leg.payment()));
        var newPassenger=user(false);code(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",leg.ride()),newPassenger,key()),403,"ACCOUNT_RESTRICTED");
        assertEquals(403,send("GET","/api/v1/rides",null,leg.driver(),null).statusCode());assertEquals(200,send("GET","/api/v1/rides/mine",null,leg.driver(),null).statusCode());
        ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));ok(send("POST",reservation(leg,"board"),Map.of("pin",pin(leg)),leg.driver(),key()));
        sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',reauthenticated_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE user_id IN (?,?)",leg.driver().id(),leg.passenger().id());app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class).run(20,()->false);
        assertEquals(0,count("SELECT count(*) FROM users WHERE id IN (?,?) AND deleted_at IS NOT NULL",leg.driver().id(),leg.passenger().id()));
        ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key()));ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()));
        assertEquals("released",state("parcels",leg.parcel()));assertEquals("scheduled",ok(send("GET","/api/v1/privacy/closure",null,leg.driver(),null)).path("status").asText());assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void closingMonthlyDriverCanClosePaidContractsWithoutRenewal()throws Exception {
        var paid=paid();requestClosure(paid.driver());assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,paid.contract()));assertEquals("paused",state("routines",paid.routine()));
        ok(send("POST","/api/v1/subscriptions/"+paid.contract()+"/remove",Map.of("reason","Synthetic closure of the existing monthly agreement"),paid.driver(),key()));
        assertEquals(0,ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void deactivationHidesNewOffersButKeepsPaidBoardingCompletionAndFinalConfirmationAvailable()throws Exception {
        var leg=avulsa();today(leg);var outsider=user(false);long before=ledger.balance(leg.passenger().id(),AccountKind.FUTURE);
        for(var user:List.of(leg.driver(),leg.passenger()))assertEquals("deactivated",ok(send("POST","/api/v1/privacy/account/deactivate",Map.of("password",PASSWORD,"acknowledged",true),user,key())).path("status").asText());
        assertEquals(before,ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals("confirmed",state("ride_reservations",leg.reservation()));
        assertEquals(404,send("GET","/api/v1/rides/"+leg.ride(),null,outsider,null).statusCode());assertEquals(403,send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",leg.ride()),outsider,key()).statusCode());
        ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));ok(send("POST",reservation(leg,"board"),Map.of("pin",pin(leg)),leg.driver(),key()));
        ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key()));ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()));
        assertEquals("released",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());assertEquals(0,count("SELECT count(*) FROM privacy_account_closures WHERE user_id IN (?,?)",leg.driver().id(),leg.passenger().id()));
    }
    @Test void reactivationDoesNotResumeMonthlyRenewalOrPausedOffersAndPaidCancellationStillCredits()throws Exception {
        var paid=paid();ok(send("POST","/api/v1/privacy/account/deactivate",Map.of("password",PASSWORD,"acknowledged",true),paid.driver(),key()));
        assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,paid.contract()));assertEquals("paused",state("routines",paid.routine()));
        ok(send("POST","/api/v1/privacy/account/reactivate",Map.of("password",PASSWORD),paid.driver(),key()));assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,paid.contract()));assertEquals("paused",state("routines",paid.routine()));
        ok(send("POST","/api/v1/subscriptions/"+paid.contract()+"/remove",Map.of("reason","Synthetic cancellation after account reactivation"),paid.driver(),key()));
        assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void correctingTheMonthlyDriversAgeRestrictsTransportAndPreservesPaidValuesForReview()throws Exception {
        var paid=paid();String birth=java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusYears(17).toString();
        var result=ok(send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth",birth),paid.driver(),key()));assertEquals("restricted",result.path("status").asText());assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,paid.driver().id()));assertEquals("approved",state("payments",paid.payment()));
        assertEquals(paid.total(),sql.queryForObject("SELECT sum(cents) FROM parcels WHERE payment_id=? AND status='blocked'",Integer.class,paid.payment()));assertEquals(0,ledger.reconciliation().difference());assertEquals(0,count("SELECT count(*) FROM privacy_registration_restrictions WHERE source_user_id=?",paid.driver().id()));assertEquals(1,count("SELECT count(*) FROM occurrences WHERE other_id=? AND status='financial_pending'",paid.driver().id()));
    }
    @Test void correctingThePaidPassengersAgeDoesNotForfeitTheParcelOrExposeTheBirthDateToTheDriver()throws Exception {
        var leg=avulsa();String birth=java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusYears(17).toString();
        ok(send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth",birth),leg.passenger(),key()));assertEquals("approved",state("payments",leg.payment()));assertEquals("blocked",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
        assertEquals(0,count("SELECT count(*) FROM notifications WHERE user_id=? AND body LIKE ?",leg.driver().id(),"%"+birth+"%"));assertEquals(0,count("SELECT count(*) FROM privacy_registration_restrictions WHERE source_user_id=?",leg.passenger().id()));
    }
    @Test void erasurePreservesThePaidLedgerAndOnlyRetainsEncryptedMinimumIdentity()throws Exception {
        var leg=avulsa();UUID route=sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,leg.ride());sql.update("UPDATE ride_routes SET origin_address='Synthetic private precise location',origin_lat=-16.1,origin_lng=-48.2 WHERE id=?",route);awaiting(leg);ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()));long net=ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE);int journals=count("SELECT count(*) FROM ledger_transactions");
        String email=sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,leg.driver().id());UUID closure=UUID.fromString(requestClosure(leg.driver()).path("id").asText());sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',reauthenticated_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE id=?",closure);
        app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class).run(20,()->false);
        assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertEquals(net,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions"));assertEquals("approved",state("payments",leg.payment()));assertEquals("released",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
        var evidenceRow=sql.queryForMap("SELECT id,content_ciphertext FROM privacy_retained_records WHERE user_id=? AND category='contract_identity'",leg.driver().id());String evidence=(String)evidenceRow.get("content_ciphertext");assertFalse(evidence.contains(email));
        var privacyCipher=app.getBean(com.unicarona.privacy.service.PrivacyEvidenceCrypto.class);
        assertTrue(privacyCipher.open(com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,leg.driver().id(),(UUID)evidenceRow.get("id"),evidence).contains(email));
        assertThrows(IllegalStateException.class,()->app.getBean(CryptoService.class).decrypt(evidence,AuthTestSettings.KEY));
        assertNull(sql.queryForObject("SELECT origin_address FROM ride_routes WHERE id=?",String.class,route));var locationRow=sql.queryForMap("SELECT id,content_ciphertext FROM privacy_retained_records WHERE user_id=? AND category='location'",leg.driver().id());String location=(String)locationRow.get("content_ciphertext");assertFalse(location.contains("Synthetic private precise location"));assertTrue(privacyCipher.open(com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,leg.driver().id(),(UUID)locationRow.get("id"),location).contains("Synthetic private precise location"));assertEquals(1,count("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND category='location' AND delete_after<created_at+interval '181 days'",leg.driver().id()));
        assertEquals(1,count("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND category='contract_identity' AND delete_after>created_at AND delete_after<created_at+interval '6 years'",leg.driver().id()));
        assertEquals(401,send("GET","/api/v1/reservations",null,leg.driver(),null).statusCode());assertEquals(404,send("GET","/api/v1/reservations/"+leg.reservation()+"/contact",null,leg.passenger(),null).statusCode());
        tx.executeWithoutResult(status->{var person=app.getBean(com.unicarona.shared.repository.ActorsRepository.class).lockUser(leg.driver().id());assertEquals("deleted",person.accountStatus());assertThrows(com.unicarona.shared.ApiException.class,()->com.unicarona.shared.repository.ActorsRepository.active(person));});
    }
    @Test void aPendingPaymentDefersErasureUntilTheExistingRequestIsCancelled()throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=offer(driver,true);UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",ride),passenger,key())).path("id").asText());UUID payment=sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,request);
        UUID closure=UUID.fromString(requestClosure(passenger).path("id").asText());sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',reauthenticated_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE id=?",closure);
        var maintenance=app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class);maintenance.run(20,()->false);assertEquals("pending_obligations",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertEquals("awaiting",state("payments",payment));assertEquals(0,count("SELECT count(*) FROM users WHERE id=? AND deleted_at IS NOT NULL",passenger.id()));
        ok(send("POST","/api/v1/ride-requests/"+request+"/cancel",Map.of(),passenger,key()));assertEquals("expired",state("payments",payment));maintenance.run(20,()->false);assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void startBoardFinishAndFinalReleaseOnlyAfterPassengerConfirmation() throws Exception {
        var leg=avulsa();today(leg);String startKey=key(),boardKey=key(),finishKey=key(),finalKey=key();int journals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",leg.payment());long commission=ledger.balance(null,AccountKind.COMMISSION);
        var start=ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),startKey));assertEquals(start,ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),startKey)));assertEquals("active",state("rides",leg.ride()));
        String pin=pin(leg);var board=ok(send("POST",reservation(leg,"board"),Map.of("pin",pin),leg.driver(),boardKey));assertEquals(board,ok(send("POST",reservation(leg,"board"),Map.of("pin",pin),leg.driver(),boardKey)));assertEquals("boarded",state("ride_reservations",leg.reservation()));assertEquals("future",state("parcels",leg.parcel()));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",leg.payment()));assertEquals(0,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));
        var finish=ok(send("POST",ride(leg,"finish"),Map.of("availability",false),leg.driver(),finishKey));assertEquals(finish,ok(send("POST",ride(leg,"finish"),Map.of("availability",false),leg.driver(),finishKey)));assertEquals("awaiting_confirmation",state("ride_reservations",leg.reservation()));assertEquals("awaiting",state("parcels",leg.parcel()));assertEquals("fulfilled",detail(leg).path("availability").asText());assertFalse(detail(leg).has("pin"));assertTrue(sql.queryForObject("SELECT confirmation_deadline BETWEEN now()+interval '23 hours 59 minutes' AND now()+interval '24 hours' FROM ride_reservations WHERE id=?",Boolean.class,leg.reservation()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));
        var result=ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),finalKey));assertEquals(result,ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),finalKey)));assertEquals("completed",state("ride_reservations",leg.reservation()));assertEquals("released",state("parcels",leg.parcel()));int fee=(leg.cents()*1000+5000)/10000;assertEquals(leg.cents()-fee,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(commission+fee,ledger.balance(null,AccountKind.COMMISSION));assertEquals(0,ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
        assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='boarding_registered' AND resource_id=?",leg.reservation()));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='ride_completed' AND resource_id=?",leg.ride()));code(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()),409,"FINAL_UNAVAILABLE");
    }
    @Test void boardingChecksDayOwnerPinStateAndPersistentAttemptLimitWithoutMoneyMovement() throws Exception {
        var leg=avulsa();String good=pin(leg),bad=good.equals("0000")?"0001":"0000";code(send("POST",reservation(leg,"board"),Map.of("pin",good),leg.driver(),key()),409,"BOARDING_UNAVAILABLE");today(leg);code(send("POST",reservation(leg,"board"),Map.of("pin",good),leg.passenger(),key()),404,"RESERVATION_NOT_FOUND");
        sql.update("DELETE FROM rate_limits");for(int index=0;index<10;index++) code(send("POST",reservation(leg,"board"),Map.of("pin",bad),leg.driver(),key()),422,"PIN_INVALID");code(send("POST",reservation(leg,"board"),Map.of("pin",good),leg.driver(),key()),429,"RATE_LIMITED");assertEquals("confirmed",state("ride_reservations",leg.reservation()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));
        sql.update("DELETE FROM rate_limits");String key=key();ok(send("POST",reservation(leg,"board"),Map.of("pin",good),leg.driver(),key));code(send("POST",reservation(leg,"board"),Map.of("pin",bad),leg.driver(),key),409,"IDEMPOTENCY_CONFLICT");assertEquals(0,count("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND scope='reservation:board' AND response->>'pin' IS NOT NULL",leg.driver().id()));assertEquals(64,sql.queryForObject("SELECT length(body_hash) FROM idempotency_keys WHERE actor_id=? AND key=? AND scope='reservation:board'",Integer.class,leg.driver().id(),key));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void monthlyNotifiedAbsencePreservesSeatAndValueThenThreeAbsencesCreateReviewOnly() throws Exception {
        var paid=paid();for(int index=0;index<4;index++) {
            var leg=leg(paid,index);if(index==0) { String key=key();var absence=ok(send("POST",reservation(leg,"absence"),Map.of("reason","  Synthetic absence  "),leg.passenger(),key));assertEquals(absence,ok(send("POST",reservation(leg,"absence"),Map.of("reason","Synthetic absence"),leg.passenger(),key)));assertEquals("confirmed",state("ride_reservations",leg.reservation()));assertEquals("future",state("parcels",leg.parcel()));assertEquals(0,ledger.balance(leg.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(1,count("SELECT count(*) FROM ride_reservations WHERE ride_id=? AND status='confirmed'",leg.ride())); }
            // Simulate a different started date without bypassing state guards; finish intentionally allows existing active commitments.
            sql.update("UPDATE rides SET status='active' WHERE id=?",leg.ride());ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key()));assertEquals(index==0?"notified_absence":"passenger_absent",detail(leg).path("absence").asText());assertEquals("fulfilled_absence",detail(leg).path("availability").asText());assertEquals("awaiting",state("parcels",leg.parcel()));
        }
        assertEquals(1,count("SELECT count(*) FROM occurrences WHERE reservation_id IN (SELECT id FROM ride_reservations WHERE subscription_id=?) AND kind='absence_review'",paid.contract()));assertEquals(0,count("SELECT count(*) FROM occurrence_parcels WHERE parcel_id IN (SELECT id FROM parcels WHERE payment_id=?)",paid.payment()));assertEquals("active",state("subscriptions",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals(4,ok(send("GET","/api/v1/subscriptions/"+paid.contract(),null,paid.passenger(),null)).path("absenceCount").asInt());assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void missingBoardingOrUnfulfilledMonthlyAvailabilityBlocksOnlyTheAffectedParcel() throws Exception {
        for(boolean monthly:List.of(false,true)) {
            Paid paid=monthly?paid():null;var leg=monthly?leg(paid,0):avulsa();begin(leg);ok(send("POST",ride(leg,"finish"),Map.of("availability",!monthly),leg.driver(),key()));assertEquals("under_review",state("ride_reservations",leg.reservation()));assertEquals("blocked",state("parcels",leg.parcel()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(1,count("SELECT count(*) FROM occurrences WHERE reservation_id=? AND kind='dispute'",leg.reservation()));assertEquals(1,count("SELECT count(*) FROM occurrence_parcels WHERE parcel_id=?",leg.parcel()));if(monthly) assertEquals(paid.total()-leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));code(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()),409,"FINAL_UNAVAILABLE");assertEquals(0,ledger.reconciliation().difference());
        }
    }
    @Test void noClassAwaitsConfirmationAndReturnsFullCreditWithoutPlatformRevenue() throws Exception {
        var paid=paid();var leg=leg(paid,0);long retained=ledger.balance(null,AccountKind.RETAINED),commission=ledger.balance(null,AccountKind.COMMISSION);String key=key();var response=ok(send("POST",ride(leg,"no-class"),Map.of("reason","Synthetic later calendar exclusion"),leg.driver(),key));assertEquals(response,ok(send("POST",ride(leg,"no-class"),Map.of("reason","Synthetic later calendar exclusion"),leg.driver(),key)));assertEquals("no_class",detail(leg).path("availability").asText());assertEquals("awaiting_confirmation",state("ride_reservations",leg.reservation()));assertEquals("awaiting",state("parcels",leg.parcel()));assertEquals(paid.total(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()));assertEquals("credited",state("parcels",leg.parcel()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(retained,ledger.balance(null,AccountKind.RETAINED));assertEquals(commission,ledger.balance(null,AccountKind.COMMISSION));assertEquals(0,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(paid.total()-leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void noClassRejectsConfirmedAvulsaAndMonthlyAbsenceRejectsAvulsa() throws Exception {
        var leg=avulsa();code(send("POST",ride(leg,"no-class"),Map.of("reason","Synthetic no class"),leg.driver(),key()),409,"AVULSA_CONFIRMED");code(send("POST",reservation(leg,"absence"),Map.of("reason","Synthetic absence"),leg.passenger(),key()),409,"ABSENCE_UNAVAILABLE");assertEquals("planned",state("rides",leg.ride()));assertEquals("future",state("parcels",leg.parcel()));
    }
    @Test void passengerAvulsaCancellationBlocksMoneyAndReusesOpenCaseOnSubsequentContest() throws Exception {
        var leg=avulsa();String key=key();var input=Map.of("reason","  Synthetic cancellation  ");var cancelled=ok(send("POST",reservation(leg,"cancel"),input,leg.passenger(),key));assertEquals(cancelled,ok(send("POST",reservation(leg,"cancel"),input,leg.passenger(),key)));assertEquals("cancelled",state("ride_reservations",leg.reservation()));assertEquals("blocked",state("parcels",leg.parcel()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.balance(leg.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.balance(leg.driver().id(),AccountKind.DRIVER_AVAILABLE));var contested=ok(send("POST",reservation(leg,"contest"),Map.of("reason","Synthetic followup"),leg.passenger(),key()));assertEquals(cancelled.path("occurrenceId").asText(),contested.path("id").asText());assertEquals(1,count("SELECT count(*) FROM occurrence_parcels WHERE parcel_id=?",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
        var paid=paid();var monthly=leg(paid,0);code(send("POST",reservation(monthly,"cancel"),Map.of("reason","Synthetic cancellation"),monthly.passenger(),key()),409,"CANCEL_UNAVAILABLE");assertEquals("future",state("parcels",monthly.parcel()));
    }
    @Test void driverCancellationCreditsEligibleParcelsAndKeepsAlreadyBlockedMoneyInAnalysis() throws Exception {
        var first=avulsa();var second=avulsa(first.driver(),user(false),first.ride());ok(send("POST",reservation(second,"contest"),Map.of("reason","Synthetic separate dispute"),second.passenger(),key()));String key=key();var response=ok(send("POST",ride(first,"cancel"),Map.of("reason","Synthetic driver cancellation"),first.driver(),key));assertEquals(response,ok(send("POST",ride(first,"cancel"),Map.of("reason","Synthetic driver cancellation"),first.driver(),key)));assertEquals("credited",state("parcels",first.parcel()));assertEquals(first.cents(),ledger.balance(first.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals("blocked",state("parcels",second.parcel()));assertEquals(second.cents(),ledger.balance(second.passenger().id(),AccountKind.BLOCKED));assertEquals("cancelled",state("ride_reservations",second.reservation()));assertEquals(1,count("SELECT count(*) FROM notifications WHERE ref_id=? AND body='A parcela contestada continua em análise.'",second.reservation()));assertEquals(0,ledger.balance(first.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void driverCancellationExpiresUnpaidRequestsAndReleasesOnlyUnpaidHolds() throws Exception {
        var paid=paid();var input=Map.of("routineId",paid.routine().toString(),"startDate",LocalDate.parse(start).plusMonths(1).toString(),"directions","ambos","weekdays",List.of(1,3,5),"previousContractId",paid.contract().toString());UUID ride=UUID.fromString(ok(send("POST","/api/v1/subscriptions/preview",input,paid.passenger(),null)).path("lines").get(0).path("rideId").asText());sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",paid.contract(),ride);
        var passenger=user(false);UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",ride),passenger,key())).path("id").asText());UUID payment=sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,request);ok(send("POST","/api/v1/rides/"+ride+"/cancel",Map.of("reason","Synthetic unpaid closure"),paid.driver(),key()));assertEquals("cancelled",state("ride_requests",request));assertEquals("expired",state("payments",payment));assertEquals(0,count("SELECT count(*) FROM subscription_schedule WHERE subscription_id=? AND held",paid.contract()));assertEquals(paid.total(),ledger.balance(paid.passenger().id(),AccountKind.FUTURE));assertEquals("active",state("subscriptions",paid.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void collectiveTimeChangeRequiresEveryPaidParticipantAndDecliningAvulsaGetsFullCredit() throws Exception {
        var first=avulsa();var second=avulsa(first.driver(),user(false),first.ride());String key=key();var input=Map.of("time","19:15","reason","  Synthetic collective proposal  ");var response=ok(send("POST",ride(first,"time-proposal"),input,first.driver(),key));assertEquals("awaiting_agreement",response.path("status").asText());assertEquals(response,ok(send("POST",ride(first,"time-proposal"),input,first.driver(),key)));assertEquals("18:30",detail(first).path("time").asText());ok(send("POST",reservation(first,"time-response"),Map.of("accept",true),first.passenger(),key()));assertEquals("18:30",detail(first).path("time").asText());String answerKey=key();var declined=ok(send("POST",reservation(second,"time-response"),Map.of("accept",false),second.passenger(),answerKey));assertEquals(declined,ok(send("POST",reservation(second,"time-response"),Map.of("accept",false),second.passenger(),answerKey)));assertEquals("19:15",detail(first).path("time").asText());assertEquals("cancelled",state("ride_reservations",second.reservation()));assertEquals("credited",state("parcels",second.parcel()));assertEquals(second.cents(),ledger.balance(second.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals("future",state("parcels",first.parcel()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void decliningMonthlyTimePreservesOriginalCommitmentAndKeepTimeWithdrawsProposal() throws Exception {
        var paid=paid();var monthly=leg(paid,0);var avulsa=avulsa(monthly.driver(),user(false),monthly.ride());ok(send("POST",ride(monthly,"time-proposal"),Map.of("time","19:30","reason","Synthetic collective change"),monthly.driver(),key()));ok(send("POST",reservation(avulsa,"time-response"),Map.of("accept",true),avulsa.passenger(),key()));ok(send("POST",reservation(monthly,"time-response"),Map.of("accept",false),monthly.passenger(),key()));assertEquals("18:00",detail(monthly).path("time").asText());assertEquals("confirmed",state("ride_reservations",monthly.reservation()));assertEquals("future",state("parcels",monthly.parcel()));assertEquals(paid.total(),ledger.balance(monthly.passenger().id(),AccountKind.FUTURE));
        String key=key();var kept=ok(send("POST",ride(monthly,"keep-time"),Map.of(),monthly.driver(),key));assertEquals(kept,ok(send("POST",ride(monthly,"keep-time"),Map.of(),monthly.driver(),key)));assertEquals("18:00",kept.path("time").asText());assertEquals("withdrawn",detail(monthly).path("timeResponse").asText());assertEquals("",detail(monthly).path("proposedTime").asText());code(send("POST",reservation(monthly,"time-response"),Map.of("accept",true),monthly.passenger(),key()),409,"TIME_PROPOSAL_UNAVAILABLE");assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void unreservedTimeUpdatesImmediatelyAndSoleDecliningAvulsaAlsoCompletesProposal() throws Exception {
        var driver=user(true);UUID ride=offer(driver,true);var response=ok(send("POST","/api/v1/rides/"+ride+"/time-proposal",Map.of("time","20:00","reason","Synthetic empty proposal"),driver,key()));assertEquals("updated",response.path("status").asText());assertEquals("20:00:00",sql.queryForObject("SELECT departure_time::text FROM rides WHERE id=?",String.class,ride));
        var leg=avulsa(driver,user(false),ride);ok(send("POST",ride(leg,"time-proposal"),Map.of("time","21:00","reason","Synthetic sole proposal"),driver,key()));ok(send("POST",reservation(leg,"time-response"),Map.of("accept",false),leg.passenger(),key()));assertEquals("21:00",detail(leg).path("time").asText());assertEquals("credited",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void finalAndContestRaceNeverReleaseBlockedMoneyOrSettleTwice() throws Exception {
        var leg=avulsa();awaiting(leg);var gate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var finalResponse=executor.submit(() -> { gate.await();return send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()); });var contestResponse=executor.submit(() -> { gate.await();return send("POST",reservation(leg,"contest"),Map.of("reason","Synthetic concurrent dispute"),leg.passenger(),key()); });gate.countDown();ok(contestResponse.get(15,TimeUnit.SECONDS));var finalResult=finalResponse.get(15,TimeUnit.SECONDS);assertTrue(Set.of(200,409).contains(finalResult.statusCode()));if(finalResult.statusCode()==409) code(finalResult,409,"FINAL_UNAVAILABLE"); }
        String state=state("parcels",leg.parcel());assertTrue(Set.of("blocked","released").contains(state));assertEquals(state.equals("blocked")?leg.cents():0,ledger.balance(leg.passenger().id(),AccountKind.BLOCKED));assertTrue(count("SELECT count(*) FROM ledger_transactions WHERE parcel_id=? AND event_key LIKE '%:settled'",leg.parcel())<=1);assertEquals(1,count("SELECT count(*) FROM occurrences WHERE reservation_id=?",leg.reservation()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void simultaneousBoardingAndFinalConfirmationAreIdempotentAndOrdered() throws Exception {
        var leg=avulsa();today(leg);String pin=pin(leg),key=key();var gate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var a=executor.submit(() -> { gate.await();return send("POST",reservation(leg,"board"),Map.of("pin",pin),leg.driver(),key); });var b=executor.submit(() -> { gate.await();return send("POST",reservation(leg,"board"),Map.of("pin",pin),leg.driver(),key); });gate.countDown();assertEquals(ok(a.get(15,TimeUnit.SECONDS)),ok(b.get(15,TimeUnit.SECONDS))); }
        ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key()));String finalKey=key();var finalGate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var a=executor.submit(() -> { finalGate.await();return send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),finalKey); });var b=executor.submit(() -> { finalGate.await();return send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),finalKey); });finalGate.countDown();assertEquals(ok(a.get(15,TimeUnit.SECONDS)),ok(b.get(15,TimeUnit.SECONDS))); }
        assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='boarding_registered' AND resource_id=?",leg.reservation()));assertEquals(1,count("SELECT count(*) FROM ledger_transactions WHERE parcel_id=? AND kind='released'",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void databaseFailureAfterFinancialHoldRollsBackDomainCaseLedgerAndKey() throws Exception {
        var leg=avulsa();String key=key();int journals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",leg.payment());sql.execute("CREATE FUNCTION synthetic_occurrence_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic occurrence failure' USING ERRCODE='23514'; END $$");sql.execute("CREATE TRIGGER synthetic_occurrence_failure BEFORE INSERT ON occurrences FOR EACH ROW EXECUTE FUNCTION synthetic_occurrence_failure()");
        try { code(send("POST",reservation(leg,"cancel"),Map.of("reason","Synthetic transactional rollback"),leg.passenger(),key),409,"INVARIANT_VIOLATION");assertEquals("confirmed",state("ride_reservations",leg.reservation()));assertEquals("future",state("parcels",leg.parcel()));assertEquals(leg.cents(),ledger.balance(leg.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(leg.passenger().id(),AccountKind.BLOCKED));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",leg.payment()));assertEquals(0,count("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='passenger_cancelled'",leg.reservation())); }
        finally { sql.execute("DROP TRIGGER synthetic_occurrence_failure ON occurrences");sql.execute("DROP FUNCTION synthetic_occurrence_failure()"); }
        ok(send("POST",reservation(leg,"cancel"),Map.of("reason","Synthetic transactional rollback"),leg.passenger(),key));assertEquals("blocked",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void existingClosuresAndContestRemainAvailableWhenAccountsAreRestricted() throws Exception {
        var leg=avulsa();boarded(leg);ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));sql.update("UPDATE users SET account_status='suspended' WHERE id=?",leg.driver().id());sql.update("UPDATE driver_profiles SET status='rejected' WHERE user_id=?",leg.driver().id());ok(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key()));sql.update("UPDATE users SET account_status='suspended' WHERE id=?",leg.passenger().id());ok(send("POST",reservation(leg,"final"),Map.of(),leg.passenger(),key()));ok(send("POST",reservation(leg,"contest"),Map.of("reason","Synthetic restricted historical report"),leg.passenger(),key()));assertEquals("released",state("parcels",leg.parcel()));assertEquals(0,count("SELECT count(*) FROM occurrence_parcels WHERE parcel_id=?",leg.parcel()));
        var next=avulsa();sql.update("UPDATE users SET account_status='suspended' WHERE id=?",next.driver().id());ok(send("POST",ride(next,"cancel"),Map.of("reason","Synthetic restricted closure"),next.driver(),key()));assertEquals("credited",state("parcels",next.parcel()));code(send("POST",ride(next,"start"),Map.of(),next.driver(),key()),403,"ACCOUNT_RESTRICTED");assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void strictBodiesOwnerRolesAndSwaggerMatchTheLifecycleContract() throws Exception {
        var leg=avulsa();var outsider=user(false);code(send("POST",ride(leg,"start"),Map.of(),outsider,key()),404,"RIDE_NOT_FOUND");code(send("POST",reservation(leg,"final"),Map.of(),leg.driver(),key()),404,"RESERVATION_NOT_FOUND");code(send("POST",ride(leg,"finish"),Map.of("availability",true),leg.driver(),key()),409,"RIDE_STATE");
        for(Object input:List.of(Map.of("availability","true"),Map.of("availability",true,"extra",1),Map.of())) code(send("POST",ride(leg,"finish"),input,leg.driver(),key()),422,"VALIDATION_ERROR");for(String pin:List.of("12","12345","abcd"," 1234")) code(send("POST",reservation(leg,"board"),Map.of("pin",pin),leg.driver(),key()),422,"VALIDATION_ERROR");for(String reason:List.of(" a "," ","x".repeat(2001))) code(send("POST",reservation(leg,"contest"),Map.of("reason",reason),leg.passenger(),key()),422,"VALIDATION_ERROR");code(send("POST",ride(leg,"start"),Map.of(),leg.driver(),null),422,"IDEMPOTENCY_REQUIRED");code(send("POST",ride(leg,"time-proposal"),Map.of("time","25:00","reason","Synthetic invalid time"),leg.driver(),key()),422,"VALIDATION_ERROR");
        var api=ok(send("GET","/api/docs/json",null,null,null));for(String path:List.of("/api/v1/rides/{id}/start","/api/v1/rides/{id}/finish","/api/v1/rides/{id}/cancel","/api/v1/rides/{id}/no-class","/api/v1/rides/{id}/time-proposal","/api/v1/rides/{id}/keep-time","/api/v1/reservations/{id}/board","/api/v1/reservations/{id}/final","/api/v1/reservations/{id}/contest","/api/v1/reservations/{id}/cancel","/api/v1/reservations/{id}/absence","/api/v1/reservations/{id}/time-response")) { var operation=api.path("paths").path(path).path("post");assertFalse(operation.isMissingNode());var headers=new HashSet<String>();operation.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) headers.add(p.path("name").asText()); });assertTrue(headers.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));assertTrue(operation.path("requestBody").path("required").asBoolean()); }
    }
    @Test void independentJavaInstancesReplayLifecycleDecisionsInBothDirections() throws Exception {
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            for(boolean peerFirst:List.of(true,false)) {
                var leg=avulsa();today(leg);
                for(String action:List.of("start","board","finish","final")) {
                    String path=Set.of("start","finish").contains(action)?ride(leg,action):reservation(leg,action);Session actor=action.equals("final")?leg.passenger():leg.driver();Object input=action.equals("board")?Map.of("pin",pin(leg)):action.equals("finish")?Map.of("availability",true):Map.of();String key=key();
                    if(peerFirst) { var old=peer.request("POST",path,input,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(old.path("body"),ok(send("POST",path,input,actor,key))); }
                    else { var response=ok(send("POST",path,input,actor,key));var old=peer.request("POST",path,input,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(response,old.path("body")); }
                }
                assertEquals("released",state("parcels",leg.parcel()));
                var oldDetail=peer.request("GET",reservation(leg,""),null,headers(leg.passenger(),null)).path("body");
                ((com.fasterxml.jackson.databind.node.ObjectNode)oldDetail.path("vehicle")).put("plate","Placa protegida");
                ((com.fasterxml.jackson.databind.node.ObjectNode)oldDetail).put("meeting","Detalhes do encontro protegidos");
                assertEquals(oldDetail,detail(leg));
                var second=avulsa();String path=ride(second,"cancel").replace(second.ride().toString(),second.ride().toString().toUpperCase(Locale.ROOT));String key=key();var input=Map.of("reason","  Synthetic cancellation parity  ");if(peerFirst) { var old=peer.request("POST",path,input,headers(second.driver(),key));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),ok(send("POST",path,input,second.driver(),key))); } else { var response=ok(send("POST",path,input,second.driver(),key));assertEquals(response,peer.request("POST",path,input,headers(second.driver(),key)).path("body")); }assertEquals("credited",state("parcels",second.parcel()));
            }
        }
        assertEquals(0,ledger.reconciliation().difference());
    }
    private void parity(JavaApplicationPeer peer,boolean peerFirst,String path,Object input,Session actor) throws Exception {
        String key=key();if(peerFirst) { var old=peer.request("POST",path,input,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(old.path("body"),ok(send("POST",path,input,actor,key))); }
        else { var response=ok(send("POST",path,input,actor,key));var old=peer.request("POST",path,input,headers(actor,key));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(response,old.path("body")); }
    }
    @Test void independentJavaInstancesReplayMonthlyAbsenceNoClassDisputesAndCollectiveTimeRules() throws Exception {
        // Either Java instance credits no-class; a replay must never settle the parcel twice.
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            for(boolean peerFirst:List.of(true,false)) {
                var paid=paid();var first=leg(paid,0);parity(peer,peerFirst,reservation(first,"absence"),Map.of("reason","  Synthetic monthly absence  "),first.passenger());parity(peer,peerFirst,ride(first,"no-class"),Map.of("reason","Synthetic later exclusion"),first.driver());parity(peer,peerFirst,reservation(first,"final"),Map.of(),first.passenger());assertEquals("credited",state("parcels",first.parcel()));parity(peer,peerFirst,reservation(first,"contest"),Map.of("reason","Synthetic historical question"),first.passenger());assertEquals(0,count("SELECT count(*) FROM occurrence_parcels WHERE parcel_id=?",first.parcel()));
                var second=leg(paid,1);parity(peer,peerFirst,ride(second,"time-proposal"),Map.of("time","22:00","reason","  Synthetic time proposal  "),second.driver());parity(peer,peerFirst,reservation(second,"time-response"),Map.of("accept",false),second.passenger());parity(peer,peerFirst,ride(second,"keep-time"),Map.of(),second.driver());assertEquals("21:00",detail(second).path("time").asText());
                var avulsa=avulsa();parity(peer,peerFirst,reservation(avulsa,"cancel"),Map.of("reason","Synthetic personal cancellation"),avulsa.passenger());parity(peer,peerFirst,reservation(avulsa,"contest"),Map.of("reason","Synthetic followup contest"),avulsa.passenger());assertEquals("blocked",state("parcels",avulsa.parcel()));assertEquals(1,count("SELECT count(*) FROM occurrences WHERE reservation_id=?",avulsa.reservation()));
            }
        }
        assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void uppercaseReservationMutationPreservesCanonicalLookupAndPreservesReplayFingerprint() throws Exception {
        var leg=avulsa();today(leg);String path=reservation(leg,"board").replace(leg.reservation().toString(),leg.reservation().toString().toUpperCase(Locale.ROOT)),key=key();var input=Map.of("pin",pin(leg));
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { var response=ok(send("POST",path,input,leg.driver(),key));assertEquals(leg.reservation().toString().toUpperCase(Locale.ROOT),response.path("id").asText());var old=peer.request("POST",path,input,headers(leg.driver(),key));assertEquals(200,old.path("status").asInt());assertEquals(response,old.path("body")); }
        assertEquals("boarded",state("ride_reservations",leg.reservation()));assertEquals("future",state("parcels",leg.parcel()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void lifecycleRetriesWholeTransactionWhenAPaidParticipantAppearsWhileWaitingForLocks() throws Exception {
        var first=avulsa();var passenger=user(false);UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",first.ride()),passenger,key())).path("id").asText());UUID payment=sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,request);int cents=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);fund(passenger,cents);String key=key();var pending=new java.util.concurrent.atomic.AtomicReference<Future<HttpResponse<String>>>();var marker=new java.util.concurrent.atomic.AtomicReference<java.sql.Timestamp>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            tx.executeWithoutResult(ignored -> {
                sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",first.driver().id());pending.set(executor.submit(() -> send("POST",reservation(first,"contest"),Map.of("reason","Synthetic participant race"),first.passenger(),key)));
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<deadline) { sql.execute("SELECT pg_stat_clear_snapshot()");if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FROM users u%' AND query LIKE '%FOR UPDATE OF u%')",Boolean.class))) { waiting=true;break; }try { Thread.sleep(10); } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); } }assertTrue(waiting,"Lifecycle did not reach the ordered participant lock");
                var actor=new com.unicarona.security.Actor(passenger.id(),"user","active",null,null,null,true,Set.of("ROLE_PASSENGER"));var prepared=app.getBean(com.unicarona.payment.service.PaymentTransactions.class).prepare(actor,payment.toString(),new com.unicarona.payment.dto.PaymentDto.CheckoutInput("pix",true,app.getBean(com.unicarona.payment.repository.PaymentReadRepository.class).summary(payment,passenger.id()).tripReview()),key());assertTrue(prepared.confirmed());marker.set(sql.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class));
            });
            ok(pending.get().get(15,TimeUnit.SECONDS));
        }
        assertTrue(sql.queryForObject("SELECT created_at>? FROM idempotency_keys WHERE actor_id=? AND key=? AND scope='reservation:contest'",Boolean.class,marker.get(),first.passenger().id(),key),"The successful key must belong to the fresh retry transaction");assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='parcel_contested' AND resource_id=?",first.reservation()));assertEquals("blocked",state("parcels",first.parcel()));assertEquals(cents,ledger.balance(passenger.id(),AccountKind.FUTURE));assertEquals(1,count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",payment));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void campusDateAndProposalInstantStayCorrectAcrossJvmZones() throws Exception {
        var previous=TimeZone.getDefault();try {
            sql.update("UPDATE universities SET time_zone='Pacific/Kiritimati' WHERE id=?",university);
            for(String zone:List.of("UTC","Asia/Tokyo","America/Sao_Paulo")) { TimeZone.setDefault(TimeZone.getTimeZone(zone));var leg=avulsa();code(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()),409,"RIDE_STATE");today(leg);code(send("POST",ride(leg,"time-proposal"),Map.of("time","00:00","reason","Synthetic past campus proposal"),leg.driver(),key()),422,"DATE_PAST");ok(send("POST",ride(leg,"start"),Map.of(),leg.driver(),key()));assertEquals("active",state("rides",leg.ride()));assertTrue(sql.queryForObject("SELECT r.ride_date=(now() AT TIME ZONE un.time_zone)::date FROM rides r JOIN universities un ON un.id=r.university_id WHERE r.id=?",Boolean.class,leg.ride())); }
        } finally { TimeZone.setDefault(previous);sql.update("UPDATE universities SET time_zone='America/Sao_Paulo' WHERE id=?",university); }
    }
}

