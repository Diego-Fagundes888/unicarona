package com.unicarona.subscription;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.subscription.service.*;
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


/** Uses real HTTP, paid monthly contracts and PostgreSQL's peer financial/state guards. */
class ContractHttpIT {
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
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('contract-university','Contract University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'contract-course','Contract Course') RETURNING id",UUID.class,university);
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
    private String path(Paid p,String action) { return "/api/v1/subscriptions/"+p.contract()+action; }
    private void academicProtected(JsonNode item){var person=item.path("driver");assertEquals("",person.path("course").asText());assertEquals("protected",person.path("periodStatus").asText());assertTrue(person.path("periodNumber").isNull());assertEquals("",person.path("classLabel").asText());}
    @Test void aPaidMonthlyReservationKeepsOperationalDetailsWithoutOpeningTheAcademicProfile()throws Exception {
        var p=paid();UUID reservation=firstReservation(p),ride=sql.queryForObject("SELECT ride_id FROM ride_reservations WHERE id=?",UUID.class,reservation);var stranger=user(false);
        var paidRide=ok(send("GET","/api/v1/rides/"+ride,null,p.passenger(),null));assertEquals("ABC1D23",paidRide.path("vehicle").path("plate").asText());academicProtected(paidRide);
        for(var viewer:List.of(p.passenger(),stranger)){var routine=ok(send("GET","/api/v1/routines/"+p.routine(),null,viewer,null));academicProtected(routine);assertTrue(routine.path("compatible").asBoolean());}
        assertEquals("Synthetic meeting",ok(send("GET","/api/v1/routines/"+p.routine(),null,p.passenger(),null)).path("meeting").asText());var own=ok(send("GET","/api/v1/routines/"+p.routine(),null,p.driver(),null)).path("driver");assertEquals("numbered",own.path("periodStatus").asText());assertEquals(3,own.path("periodNumber").asInt());assertEquals("A",own.path("classLabel").asText());
        sql.update("UPDATE routines SET status='paused' WHERE id=?",p.routine());var linked=ok(send("GET","/api/v1/routines/"+p.routine(),null,p.passenger(),null));academicProtected(linked);assertEquals("Synthetic meeting",linked.path("meeting").asText());code(send("GET","/api/v1/routines/"+p.routine(),null,stranger,null),404,"ROUTINE_NOT_FOUND");
        assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p.payment()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void participantNamesAndMeetingDetailsAreMinimizedAcrossOffersRequestsContractsAndPayments()throws Exception {
        var p=paid();var stranger=user(false);UUID reservation=firstReservation(p);
        UUID ride=sql.queryForObject("SELECT ride_id FROM ride_reservations WHERE id=?",UUID.class,reservation);
        UUID request=sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,p.payment());
        sql.update("UPDATE users SET name='Maria Clara de Oliveira' WHERE id=?",p.driver().id());sql.update("UPDATE users SET name='João Pedro dos Santos' WHERE id=?",p.passenger().id());
        String privateMeeting="Synthetic private building 42",privateNote="Synthetic private operational note";
        sql.update("UPDATE ride_routes SET meeting=? WHERE id=(SELECT route_id FROM routines WHERE id=?)",privateMeeting,p.routine());
        sql.update("UPDATE routines SET notes=? WHERE id=?",privateNote,p.routine());sql.update("UPDATE rides SET notes=? WHERE id=?",privateNote,ride);
        assertEquals("Maria Clara de Oliveira",ok(send("GET","/api/v1/auth/me",null,p.driver(),null)).path("name").asText());
        assertEquals("Maria Clara de Oliveira",ok(send("GET","/api/v1/rides/"+ride,null,p.driver(),null)).path("driver").path("name").asText());
        assertEquals("Maria O.",ok(send("GET","/api/v1/ride-requests/"+request,null,p.passenger(),null)).path("otherName").asText());
        assertEquals("João S.",ok(send("GET","/api/v1/ride-requests/"+request,null,p.driver(),null)).path("otherName").asText());
        assertEquals("João S.",ok(send("GET","/api/v1/ride-requests?side=driver",null,p.driver(),null)).path("items").get(0).path("other").path("name").asText());
        assertEquals("Maria O.",ok(send("GET",path(p,""),null,p.passenger(),null)).path("otherName").asText());
        assertEquals("Maria O.",ok(send("GET","/api/v1/payments/"+p.payment()+"/summary",null,p.passenger(),null)).path("driverName").asText());
        var own=ok(send("GET",reservation(reservation,""),null,p.passenger(),null));assertEquals("Maria O.",own.path("name").asText());assertEquals(privateMeeting,own.path("meeting").asText());
        var offer=ok(send("GET","/api/v1/rides/"+ride,null,stranger,null));assertEquals("Maria O.",offer.path("driver").path("name").asText());assertEquals("Disponível após confirmação",offer.path("meeting").asText());assertEquals("",offer.path("notes").asText());
        String date=sql.queryForObject("SELECT ride_date::text FROM rides WHERE id=?",String.class,ride),direction=sql.queryForObject("SELECT direction FROM rides WHERE id=?",String.class,ride);
        var offers=ok(send("GET","/api/v1/rides?date="+date+"&direction="+direction+"&driverId="+p.driver().id(),null,stranger,null)).path("items");assertEquals(1,offers.size());assertEquals("Maria O.",offers.get(0).path("driver").path("name").asText());assertFalse(offers.toString().contains(privateMeeting));assertFalse(offers.toString().contains(privateNote));
        var routine=ok(send("GET","/api/v1/routines/"+p.routine(),null,stranger,null));assertEquals("Maria O.",routine.path("driver").path("name").asText());assertEquals("Disponível após confirmação",routine.path("meeting").asText());assertEquals("",routine.path("notes").asText());
        assertEquals(privateMeeting,ok(send("GET","/api/v1/routines/"+p.routine(),null,p.passenger(),null)).path("meeting").asText());
        ok(send("POST","/api/v1/waitlist",Map.of("routineId",p.routine().toString()),stranger,key()));
        var interests=ok(send("GET","/api/v1/waitlist",null,stranger,null)).path("items");assertEquals(1,interests.size());assertEquals("Maria O.",interests.get(0).path("driverName").asText());
        sql.update("DELETE FROM student_profiles WHERE user_id=?",p.passenger().id());
        assertEquals(privateMeeting,ok(send("GET","/api/v1/routines/"+p.routine(),null,p.passenger(),null)).path("meeting").asText());
        // An additional accepted quote must not downgrade the rights of this already paid leg.
        UUID overlapping=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,routine_id,type,status,expires_at) VALUES(?,?,?,'mensal','accepted',now()+interval '1 hour') RETURNING id",UUID.class,p.passenger().id(),p.driver().id(),p.routine());
        sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,600,1000)",overlapping,ride);
        sql.update("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at) VALUES(?,?,600,'development','awaiting',now()+interval '1 hour')",overlapping,p.passenger().id());
        assertEquals("ABC1D23",ok(send("GET","/api/v1/rides/"+ride,null,p.passenger(),null)).path("vehicle").path("plate").asText());
        assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void aHistoricalContractDoesNotExposeAChangedRoutineRouteOrBypassAnInactiveOffer()throws Exception {
        var p=paid();UUID reservation=firstReservation(p),oldRoute=sql.queryForObject("SELECT route_id FROM routines WHERE id=?",UUID.class,p.routine());
        UUID replacement=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Gama','Synthetic replacement private stop',10000) RETURNING id",UUID.class,university,p.driver().id());
        sql.update("UPDATE routines SET route_id=? WHERE id=?",replacement,p.routine());
        var visible=ok(send("GET","/api/v1/routines/"+p.routine(),null,p.passenger(),null));assertEquals("Disponível após confirmação",visible.path("meeting").asText());
        sql.update("UPDATE routines SET status='paused' WHERE id=?",p.routine());
        code(send("GET","/api/v1/routines/"+p.routine(),null,p.passenger(),null),404,"ROUTINE_NOT_FOUND");
        var old=ok(send("GET",reservation(reservation,""),null,p.passenger(),null));assertEquals("Synthetic meeting",old.path("meeting").asText());assertTrue(old.path("pin").asText().matches("[0-9]{4}"));
        assertEquals("Synthetic replacement private stop",ok(send("GET","/api/v1/routines/"+p.routine(),null,p.driver(),null)).path("meeting").asText());
        assertEquals(oldRoute,sql.queryForObject("SELECT r.route_id FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id WHERE rr.id=?",UUID.class,reservation));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void aRemovedPeerCannotBeIdentifiedByAnUnscrubbedDisplayColumnInAnInconsistentRestoredRecord()throws Exception {
        var p=paid();UUID reservation=firstReservation(p),request=sql.queryForObject("SELECT request_id FROM payments WHERE id=?",UUID.class,p.payment());
        sql.update("UPDATE users SET name='Synthetic former private full name',deleted_at=now(),account_status='deleted' WHERE id=?",p.driver().id());
        assertEquals("Conta excluída",ok(send("GET",reservation(reservation,""),null,p.passenger(),null)).path("name").asText());
        assertEquals("Conta excluída",ok(send("GET","/api/v1/ride-requests/"+request,null,p.passenger(),null)).path("otherName").asText());
        assertEquals("Conta excluída",ok(send("GET",path(p,""),null,p.passenger(),null)).path("otherName").asText());
        assertEquals("Conta excluída",ok(send("GET","/api/v1/payments/"+p.payment()+"/summary",null,p.passenger(),null)).path("driverName").asText());
        code(send("GET",reservation(reservation,"/contact"),null,p.passenger(),null),404,"CONTACT_UNAVAILABLE");
        assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void acceptedRequestsDiscloseTheMeetingBeforePaymentWithoutUnlockingContactPhotoOrPlate()throws Exception {
        var driver=user(true);var passenger=user(false);var stranger=user(false);UUID routine=publish(driver);
        sql.update("UPDATE routines SET automatic=false WHERE id=?",routine);
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","mensal","routineId",routine.toString(),"startDate",start,"directions","ambos","weekdays",List.of(1,3,5)),passenger,key())).path("id").asText());
        UUID ride=sql.queryForObject("SELECT ride_id FROM request_lines WHERE request_id=? ORDER BY ride_id LIMIT 1",UUID.class,request);
        assertEquals("Disponível após confirmação",ok(send("GET","/api/v1/rides/"+ride,null,passenger,null)).path("meeting").asText());
        UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),driver,key())).path("paymentId").asText());
        assertEquals("awaiting",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));
        assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE payment_id=?",payment));
        var accepted=ok(send("GET","/api/v1/rides/"+ride,null,passenger,null));assertEquals("Synthetic meeting",accepted.path("meeting").asText());assertEquals("Disponível após confirmação",accepted.path("vehicle").path("plate").asText());
        assertEquals("Synthetic meeting",ok(send("GET","/api/v1/routines/"+routine,null,passenger,null)).path("meeting").asText());
        academicProtected(ok(send("GET","/api/v1/routines/"+routine,null,passenger,null)));academicProtected(ok(send("GET","/api/v1/rides/"+ride,null,passenger,null)));
        assertEquals("Disponível após confirmação",ok(send("GET","/api/v1/rides/"+ride,null,stranger,null)).path("meeting").asText());
        UUID photo=sql.queryForObject("INSERT INTO stored_files(owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,'photo',?,'image/webp',50,repeat('0',64)) RETURNING id",UUID.class,driver.id(),"synthetic-before-payment-"+UUID.randomUUID());
        sql.update("UPDATE users SET photo_file_id=? WHERE id=?",photo,driver.id());assertEquals(404,send("GET","/api/v1/files/"+photo,null,passenger,null).statusCode());
        sql.update("UPDATE ride_requests SET expires_at=now()-interval '1 second' WHERE id=?",request);
        assertEquals("Disponível após confirmação",ok(send("GET","/api/v1/rides/"+ride,null,passenger,null)).path("meeting").asText());assertEquals("Disponível após confirmação",ok(send("GET","/api/v1/routines/"+routine,null,passenger,null)).path("meeting").asText());
        sql.update("UPDATE ride_requests SET expires_at=now()+interval '1 hour' WHERE id=?",request);sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());
        code(send("GET","/api/v1/rides/"+ride,null,passenger,null),404,"RIDE_NOT_FOUND");code(send("GET","/api/v1/routines/"+routine,null,passenger,null),404,"ROUTINE_NOT_FOUND");assertEquals(0,ledger.reconciliation().difference());
    }
    private Paid renew(Paid previous) throws Exception {
        var input=Map.of("type","mensal","routineId",previous.routine().toString(),"startDate",LocalDate.parse(start).plusMonths(1).toString(),"directions","ambos","weekdays",List.of(1,3,5),"previousContractId",previous.contract().toString());
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",input,previous.passenger(),key())).path("id").asText());
        UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),previous.driver(),key())).path("paymentId").asText());
        int total=count("SELECT amount_cents FROM payments WHERE id=?",payment);
        tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer("synthetic-renewal-funding-"+UUID.randomUUID(),"synthetic_funding","Synthetic renewal fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-total),new Posting(previous.passenger().id(),AccountKind.PASSENGER_CREDIT,total)))));
        assertTrue(ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",true),previous.passenger(),key())).path("confirmed").asBoolean());
        UUID contract=sql.queryForObject("SELECT DISTINCT subscription_id FROM ride_reservations WHERE payment_id=?",UUID.class,payment);
        return new Paid(previous.driver(),previous.passenger(),previous.routine(),payment,contract,total,count("SELECT count(*) FROM parcels WHERE payment_id=?",payment));
    }
    @Test void driverRemovalClosesFuturePaidPeriodsAcrossEarlyRenewal() throws Exception {
        for(boolean selectPrevious:List.of(false,true)) {
            var previous=paid();var next=renew(previous);
            assertEquals("renewed",read(previous,previous.driver()).path("status").asText());
            assertTrue(rights(previous,previous.driver()));
            assertEquals(previous.legs(),count("SELECT count(*) FROM ride_reservations WHERE subscription_id=? AND status='confirmed'",previous.contract()));
            int previousJournals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",previous.payment());
            int nextJournals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",next.payment());
            Paid selected=selectPrevious?previous:next;String idempotency=key();
            var removed=ok(send("POST",path(selected,"/remove"),Map.of("reason","Synthetic linked paid periods closure"),selected.driver(),idempotency));
            assertEquals(removed,ok(send("POST",path(selected,"/remove"),Map.of("reason","Synthetic linked paid periods closure"),selected.driver(),idempotency)));
            assertEquals("ended",read(previous,previous.passenger()).path("status").asText());assertEquals("ended",read(next,next.passenger()).path("status").asText());
            assertFalse(rights(previous,previous.passenger()));assertFalse(rights(next,next.passenger()));
            assertEquals(previous.legs()+next.legs(),count("SELECT count(*) FROM ride_reservations WHERE subscription_id IN (?,?) AND status='cancelled'",previous.contract(),next.contract()));
            assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));
            assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(previous.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(0,ledger.reconciliation().difference());
            assertEquals(previousJournals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",previous.payment()));assertEquals(nextJournals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=? AND kind='receipt'",next.payment()));
            code(send("POST",path(selected,"/remove"),Map.of("reason","Synthetic repeated closure"),selected.driver(),key()),409,"SUBSCRIPTION_STATE");
        }
    }
    @Test void personalBlockIncludesPaidFutureRightsOfTheRenewedPeriod() throws Exception {
        var previous=paid();var next=renew(previous);
        ok(send("POST","/api/v1/blocks",Map.of("otherId",previous.driver().id().toString(),"reason","Synthetic early renewal safety interruption"),previous.passenger(),key()));
        for(Paid period:List.of(previous,next)) {
            assertEquals("interrupted",read(period,period.passenger()).path("status").asText());
            assertEquals(period.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='blocked'",period.payment()));
            assertEquals(period.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='cancelled'",period.payment()));
        }
        assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void academicChangeIncludesPaidFutureRightsOfTheRenewedPeriod() throws Exception {
        var previous=paid();var next=renew(previous);
        var academic=Map.of("universityId",university.toString(),"courseId",course.toString(),"periodStatus","numbered","periodNumber",3,"shift","night","region","Gama","classLabel","B");
        ok(send("PATCH","/api/v1/users/me",Map.of("name","Synthetic Contract","academic",academic,"acknowledgeMonthlyImpact",true),previous.passenger(),key()));
        for(Paid period:List.of(previous,next)) {assertEquals("ended",read(period,period.passenger()).path("status").asText());assertEquals(period.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='credited'",period.payment()));}
        assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void suspensionIncludesPaidFutureRightsOfTheRenewedPeriod() throws Exception {
        var previous=paid();var next=renew(previous);var admin=user(false);sql.update("UPDATE users SET role='admin' WHERE id=?",admin.id());
        var response=ok(send("POST","/api/v1/admin/accounts/"+previous.driver().id()+"/review",Map.of("status","suspended","expectedStatus","active","reason","Synthetic early renewal account review"),admin,key()));
        assertEquals(previous.legs()+next.legs(),response.path("affectedParcels").asInt());
        for(Paid period:List.of(previous,next)) {assertEquals("interrupted",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,period.contract()));assertEquals(period.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='blocked'",period.payment()));}
        assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void linkedClosureCancelsAnUnpaidFurtherRenewal() throws Exception {
        var previous=paid();var next=renew(previous);
        var input=Map.of("type","mensal","routineId",next.routine().toString(),"startDate",LocalDate.parse(start).plusMonths(2).toString(),"directions","ambos","weekdays",List.of(1,3,5),"previousContractId",next.contract().toString());
        UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",input,next.passenger(),key())).path("id").asText());
        UUID payment=UUID.fromString(ok(send("POST","/api/v1/ride-requests/"+request+"/respond",Map.of("accept",true),next.driver(),key())).path("paymentId").asText());
        ok(send("POST",path(next,"/remove"),Map.of("reason","Synthetic closure with unpaid next period"),next.driver(),key()));
        assertEquals("cancelled",sql.queryForObject("SELECT status FROM ride_requests WHERE id=?",String.class,request));assertEquals("expired",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment));assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE payment_id=?",payment));
        assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void concurrentRemovalUsingEitherLinkedPeriodCannotDoubleCredit() throws Exception {
        var previous=paid();var next=renew(previous);var gate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{gate.await();return send("POST",path(previous,"/remove"),Map.of("reason","Synthetic parallel linked closure"),previous.driver(),key());});
            var b=executor.submit(()->{gate.await();return send("POST",path(next,"/remove"),Map.of("reason","Synthetic parallel linked closure"),next.driver(),key());});gate.countDown();
            assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(20,TimeUnit.SECONDS).statusCode(),b.get(20,TimeUnit.SECONDS).statusCode()).sorted().toList());
        }
        assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(2,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id IN (?,?)",previous.contract(),next.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void linkedClosurePreservesAnEntirelyPastRenewedPeriod() throws Exception {
        var previous=paid();var next=renew(previous);
        sql.update("UPDATE rides SET ride_date=ride_date-interval '3 months' WHERE id IN (SELECT ride_id FROM ride_reservations WHERE subscription_id=?)",previous.contract());
        assertFalse(rights(previous,previous.driver()));
        ok(send("POST",path(next,"/remove"),Map.of("reason","Synthetic future period closure after past period"),next.driver(),key()));
        assertEquals("renewed",read(previous,previous.passenger()).path("status").asText());assertEquals(previous.legs(),count("SELECT count(*) FROM ride_reservations WHERE subscription_id=? AND status='confirmed'",previous.contract()));
        assertEquals(next.total(),ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(previous.total(),ledger.balance(previous.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void renewedPeriodCannotReturnToActiveAndAcceptAnotherRenewal() throws Exception {
        var previous=paid();var next=renew(previous);
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE subscriptions SET status='active' WHERE id=?",previous.contract()));
        assertEquals("renewed",read(previous,previous.passenger()).path("status").asText());assertEquals("active",read(next,next.passenger()).path("status").asText());
        assertEquals(previous.total()+next.total(),ledger.balance(previous.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(previous.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());
    }
    private JsonNode read(Paid p,Session actor) throws Exception { return ok(send("GET",path(p,""),null,actor,null)); }
    private boolean rights(Paid p,Session actor) throws Exception { return ok(send("GET",path(p,"/rights"),null,actor,null)).path("openRights").asBoolean(); }
    @Test void futureRightsAreOwnedReadOnlyAndKeepTheOriginalContractResponse() throws Exception {
        var p=paid();assertTrue(rights(p,p.driver()));assertTrue(rights(p,p.passenger()));assertFalse(read(p,p.passenger()).has("openRights"));
        code(send("GET",path(p,"/rights"),null,user(false),null),404,"SUBSCRIPTION_NOT_FOUND");
        code(send("GET",path(p,"/rights")+"?price=1",null,p.passenger(),null),422,"VALIDATION_ERROR");
        code(send("GET",path(p,"/rights"),null,null,null),401,"UNAUTHENTICATED");
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",p.passenger().id());assertTrue(rights(p,p.passenger()));
        code(send("POST",path(p,"/cancel-renewal"),Map.of(),p.passenger(),key()),403,"ACCOUNT_RESTRICTED");
    }
    private UUID hold(Paid p) throws Exception {
        var input=Map.of("routineId",p.routine().toString(),"startDate",LocalDate.parse(start).plusMonths(1).toString(),"directions","ambos","weekdays",List.of(1,3,5),"previousContractId",p.contract().toString());
        UUID ride=UUID.fromString(ok(send("POST","/api/v1/subscriptions/preview",input,p.passenger(),null)).path("lines").get(0).path("rideId").asText());
        sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",p.contract(),ride);return ride;
    }
    private JsonNode wait(Session actor,UUID routine,String key) throws Exception { return ok(send("POST","/api/v1/waitlist",Map.of("routineId",routine.toString()),actor,key)); }
    private int count(String query,Object... args) { return sql.queryForObject(query,Integer.class,args); }
    private void ended(Paid p,int creditedLegs,long creditedCents) {
        assertEquals("ended",sql.queryForObject("SELECT status FROM subscriptions WHERE id=?",String.class,p.contract()));
        assertEquals(creditedLegs,count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='credited'",p.payment()));
        assertEquals(creditedCents,ledger.balance(p.passenger().id(),AccountKind.PASSENGER_CREDIT));
        assertEquals(0,ledger.balance(p.driver().id(),AccountKind.DRIVER_AVAILABLE));assertEquals(0,ledger.reconciliation().difference());
        assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,p.payment()));
        assertFalse(sql.queryForObject("SELECT renew FROM subscriptions WHERE id=?",Boolean.class,p.contract()));
    }
    @Test void readsHaveFrozenDatesCountsAndOwnerIsolationIncludingRestrictedHistory() throws Exception {
        var p=paid();hold(p);var item=read(p,p.passenger());assertEquals(start,item.path("startDate").asText());assertEquals(LocalDate.parse(start).plusMonths(1).minusDays(1).toString(),item.path("endDate").asText());assertEquals(0.1,item.path("discount").asDouble());assertEquals(1,item.path("heldLegs").asInt());assertEquals(json.valueToTree(List.of(1,3,5)),item.path("weekdays"));
        assertEquals(item,read(p,p.driver()));assertEquals(item,ok(send("GET","/api/v1/subscriptions",null,p.passenger(),null)).path("items").get(0));assertEquals(item,ok(send("GET","/api/v1/subscriptions?side=driver",null,p.driver(),null)).path("items").get(0));
        assertTrue(ok(send("GET","/api/v1/subscriptions",null,p.driver(),null)).path("items").isEmpty());code(send("GET",path(p,""),null,user(false),null),404,"SUBSCRIPTION_NOT_FOUND");
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",p.passenger().id());assertEquals(item,read(p,p.passenger()));code(send("POST",path(p,"/cancel-renewal"),Map.of(),p.passenger(),key()),403,"ACCOUNT_RESTRICTED");code(send("GET","/api/v1/waitlist",null,p.passenger(),null),403,"ACCOUNT_RESTRICTED");
    }
    @Test void cancellingRenewalPreservesEveryPaidLegAndReleasesOnlyHoldsWithIdempotentAudit() throws Exception {
        var p=paid();hold(p);String key=key(),path=path(p,"/cancel-renewal");int journals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",p.payment());
        var response=ok(send("POST",path,Map.of(),p.passenger(),key));assertEquals("cancelled_renewal",response.path("status").asText());assertEquals(response,ok(send("POST",path,Map.of(),p.passenger(),key)));assertEquals(0,read(p,p.passenger()).path("heldLegs").asInt());
        assertEquals(p.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",p.payment()));assertEquals(p.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='future'",p.payment()));assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals(0,ledger.balance(p.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",p.payment()));assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='subscription_renewal_cancelled' AND resource_id=?",p.contract()));
        ok(send("POST",path,Map.of(),p.passenger(),key()));assertEquals(2,count("SELECT count(*) FROM audit_logs WHERE action='subscription_renewal_cancelled' AND resource_id=?",p.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void removalCreditsFullFutureMoneyWithoutCommissionAndPromotesExistingInterest() throws Exception {
        var p=paid();hold(p);UUID waiting=UUID.fromString(wait(p.passenger(),p.routine(),key()).path("id").asText());String key=key();var input=Map.of("reason","  Synthetic closure  ");
        var response=ok(send("POST",path(p,"/remove"),input,p.driver(),key));assertEquals(response,ok(send("POST",path(p,"/remove"),input,p.driver(),key)));ended(p,p.legs(),p.total());assertEquals(0,ledger.balance(p.passenger().id(),AccountKind.FUTURE));
        assertEquals(p.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='cancelled' AND cancel_reason='Synthetic closure'",p.payment()));assertEquals(0,count("SELECT count(*) FROM subscription_schedule WHERE subscription_id=? AND held",p.contract()));assertEquals(1,count("SELECT count(*) FROM waitlist WHERE user_id=? AND routine_id=? AND status='waiting' AND priority",p.passenger().id(),p.routine()));assertEquals(waiting,sql.queryForObject("SELECT id FROM waitlist WHERE user_id=? AND routine_id=? AND status='waiting'",UUID.class,p.passenger().id(),p.routine()));
        assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",p.contract()));assertEquals(p.legs(),sql.queryForObject("SELECT (metadata->>'affected')::int FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",Integer.class,p.contract()));assertEquals(1,count("SELECT count(*) FROM notifications WHERE user_id=? AND route='contract' AND ref_id=?",p.passenger().id(),p.contract()));
        code(send("POST",path(p,"/remove"),input,p.driver(),key()),409,"SUBSCRIPTION_STATE");
    }
    @Test void removalRetainsPastStartedBoardedAndReviewedEvidenceAndNeverCreditsBlockedMoney() throws Exception {
        var p=paid();var rows=sql.queryForList("SELECT rr.id,rr.ride_id,p.id AS parcel,p.cents FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=? ORDER BY rr.id",p.payment());assertTrue(rows.size()>5);
        sql.update("UPDATE rides SET ride_date=current_date-1 WHERE id=?",rows.get(0).get("ride_id"));sql.update("UPDATE rides SET status='active' WHERE id=?",rows.get(1).get("ride_id"));sql.update("UPDATE ride_reservations SET status='boarded',boarded_at=now() WHERE id=?",rows.get(2).get("id"));sql.update("UPDATE ride_reservations SET status='under_review' WHERE id=?",rows.get(3).get("id"));
        tx.executeWithoutResult(status -> { app.getBean(ParcelService.class).block((UUID)rows.get(3).get("parcel"),"Synthetic review");app.getBean(ParcelService.class).block((UUID)rows.get(4).get("parcel"),"Synthetic separate review"); });
        long retained=rows.subList(0,5).stream().mapToLong(r -> ((Number)r.get("cents")).longValue()).sum();long blocked=((Number)rows.get(3).get("cents")).longValue()+((Number)rows.get(4).get("cents")).longValue();
        ok(send("POST",path(p,"/remove"),Map.of("reason","Synthetic future removal"),p.driver(),key()));ended(p,p.legs()-5,p.total()-retained);assertEquals(blocked,ledger.balance(p.passenger().id(),AccountKind.BLOCKED));assertEquals(retained-blocked,ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals(4,count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status<>'cancelled'",p.payment()));assertEquals("cancelled",sql.queryForObject("SELECT status FROM ride_reservations WHERE id=?",String.class,rows.get(4).get("id")));assertEquals(2,count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='blocked'",p.payment()));
    }
    @Test void internalBlockedClosureKeepsMoneyInAnalysisAndEssentialNoticeDespitePreferences() throws Exception {
        var p=paid();hold(p);sql.update("UPDATE student_profiles SET notices=false WHERE user_id=?",p.passenger().id());
        var affected=tx.execute(status -> { var locked=app.getBean(ContractLocks.class).lock(p.contract(),null,"passenger");return app.getBean(FutureContractClosure.class).close(locked.contract(),null,"Synthetic safety review",FutureContractClosure.Outcome.BLOCKED); });
        assertNotNull(affected);assertEquals(p.legs(),affected.size());assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.BLOCKED));assertEquals(0,ledger.balance(p.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals("interrupted",read(p,p.passenger()).path("status").asText());assertEquals(0,read(p,p.passenger()).path("heldLegs").asInt());assertEquals(p.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='cancelled'",p.payment()));assertEquals(1,count("SELECT count(*) FROM notifications WHERE ref_id=? AND body='Parcelas futuras seguem análise separada.'",p.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void concurrentRemovalReplaysOnceAndDifferentKeysCannotDoubleCredit() throws Exception {
        for(boolean sameKey:List.of(true,false)) {
            var p=paid();String firstKey=key(),secondKey=sameKey?firstKey:key();var gate=new CountDownLatch(1);
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var one=executor.submit(() -> { gate.await();return send("POST",path(p,"/remove"),Map.of("reason","Synthetic concurrent closure"),p.driver(),firstKey); });var two=executor.submit(() -> { gate.await();return send("POST",path(p,"/remove"),Map.of("reason","Synthetic concurrent closure"),p.driver(),secondKey); });gate.countDown();var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);
                assertEquals(sameKey?List.of(200,200):List.of(200,409),java.util.stream.Stream.of(a.statusCode(),b.statusCode()).sorted().toList());if(sameKey) assertEquals(tree(a),tree(b));else code(a.statusCode()==409?a:b,409,"SUBSCRIPTION_STATE");
            }
            ended(p,p.legs(),p.total());assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",p.contract()));
        }
    }
    @Test void passengerCancelAndDriverRemoveUseConsistentParticipantLocks() throws Exception {
        var p=paid();var gate=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(() -> { gate.await();return send("POST",path(p,"/cancel-renewal"),Map.of(),p.passenger(),key()); });var two=executor.submit(() -> { gate.await();return send("POST",path(p,"/remove"),Map.of("reason","Synthetic concurrent closure"),p.driver(),key()); });gate.countDown();var cancel=one.get(20,TimeUnit.SECONDS);ok(two.get(20,TimeUnit.SECONDS));assertTrue(Set.of(200,409).contains(cancel.statusCode()));if(cancel.statusCode()==409) code(cancel,409,"SUBSCRIPTION_STATE");
        }
        ended(p,p.legs(),p.total());assertEquals(1,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",p.contract()));
    }
    @Test void lateDatabaseFailureRollsBackCreditsReservationsNoticeAuditAndIdempotency() throws Exception {
        var p=paid();String key=key();int journals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",p.payment());
        // This additional trigger exists only in the guarded isolated test database; peer guards remain enabled.
        sql.execute("CREATE FUNCTION synthetic_waitlist_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic waitlist failure' USING ERRCODE='23514'; END $$");
        sql.execute("CREATE TRIGGER synthetic_waitlist_failure BEFORE INSERT OR UPDATE ON waitlist FOR EACH ROW EXECUTE FUNCTION synthetic_waitlist_failure()");
        try { code(send("POST",path(p,"/remove"),Map.of("reason","Synthetic rollback"),p.driver(),key),409,"INVARIANT_VIOLATION");assertEquals("active",read(p,p.passenger()).path("status").asText());assertEquals(p.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",p.payment()));assertEquals(p.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=? AND status='future'",p.payment()));assertEquals(0,ledger.balance(p.passenger().id(),AccountKind.PASSENGER_CREDIT));assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",p.payment()));assertEquals(0,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",p.contract()));assertEquals(0,count("SELECT count(*) FROM notifications WHERE route='contract' AND ref_id=?",p.contract())); }
        finally { sql.execute("DROP TRIGGER synthetic_waitlist_failure ON waitlist");sql.execute("DROP FUNCTION synthetic_waitlist_failure()"); }
        ok(send("POST",path(p,"/remove"),Map.of("reason","Synthetic rollback"),p.driver(),key));ended(p,p.legs(),p.total());
    }
    @Test void waitlistHasOneWaitingItemUnderConcurrencyWithoutReservationsOrHolds() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver);String key=key();var gate=new CountDownLatch(1);JsonNode saved;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) { var a=executor.submit(() -> { gate.await();return wait(passenger,routine,key); });var b=executor.submit(() -> { gate.await();return wait(passenger,routine,key); });gate.countDown();saved=a.get(15,TimeUnit.SECONDS);assertEquals(saved,b.get(15,TimeUnit.SECONDS)); }
        assertEquals(saved,wait(passenger,routine,key()));assertEquals(1,count("SELECT count(*) FROM waitlist WHERE user_id=? AND routine_id=? AND status='waiting'",passenger.id(),routine));assertEquals(2,count("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='waitlist_joined'",passenger.id()));assertEquals(0,count("SELECT count(*) FROM subscriptions WHERE routine_id=?",routine));assertEquals(0,count("SELECT count(*) FROM ride_reservations WHERE passenger_id=?",passenger.id()));assertEquals(0,ledger.balance(passenger.id(),AccountKind.FUTURE));
        var item=ok(send("GET","/api/v1/waitlist",null,passenger,null)).path("items").get(0);assertEquals(saved.path("id"),item.path("id"));assertFalse(item.path("priority").asBoolean());assertEquals("Gama",item.path("region").asText());
    }
    @Test void interestEligibilityMatchesOriginalEvenForPausedRoutineAndUnapprovedDriver() throws Exception {
        var driver=user(true);var passenger=user(false);var wrong=user(false,otherClass);UUID routine=publish(driver);
        code(send("POST","/api/v1/waitlist",Map.of("routineId",routine),driver,key()),422,"CLASS_MISMATCH");code(send("POST","/api/v1/waitlist",Map.of("routineId",routine),wrong,key()),422,"CLASS_MISMATCH");
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());code(send("POST","/api/v1/waitlist",Map.of("routineId",routine),passenger,key()),409,"COMBINATION_UNAVAILABLE");sql.update("UPDATE user_blocks SET active=false WHERE by_id=?",driver.id());
        sql.update("UPDATE routines SET status='paused' WHERE id=?",routine);sql.update("UPDATE driver_profiles SET status='rejected' WHERE user_id=?",driver.id());wait(passenger,routine,key());
        sql.update("UPDATE users SET email_verified_at=NULL WHERE id=?",driver.id());code(send("POST","/api/v1/waitlist",Map.of("routineId",routine),passenger,key()),403,"EMAIL_UNVERIFIED");sql.update("UPDATE users SET email_verified_at=now(),account_status='suspended' WHERE id=?",driver.id());code(send("POST","/api/v1/waitlist",Map.of("routineId",routine),passenger,key()),403,"ACCOUNT_RESTRICTED");
    }
    @Test void leavingIsOwnedRepeatableWithoutKeyAndRejoiningCreatesNewInterest() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver);UUID saved=UUID.fromString(wait(passenger,routine,key()).path("id").asText());String path="/api/v1/waitlist/"+saved.toString().toUpperCase(Locale.ROOT)+"/leave";
        code(send("POST",path,Map.of(),user(false),null),404,"WAITLIST_NOT_FOUND");var response=ok(send("POST",path,Map.of(),passenger,null));assertEquals(saved.toString().toUpperCase(Locale.ROOT),response.path("id").asText());assertEquals(response,ok(send("POST",path,Map.of(),passenger,null)));assertTrue(ok(send("GET","/api/v1/waitlist",null,passenger,null)).path("items").isEmpty());assertNotEquals(saved.toString(),wait(passenger,routine,key()).path("id").asText());assertEquals(1,count("SELECT count(*) FROM waitlist WHERE user_id=? AND status='waiting'",passenger.id()));
    }
    @Test void removalRequiresOwnerApprovedDriverAndCancelRequiresPassenger() throws Exception {
        var p=paid();code(send("POST",path(p,"/cancel-renewal"),Map.of(),p.driver(),key()),404,"SUBSCRIPTION_NOT_FOUND");code(send("POST",path(p,"/remove"),Map.of("reason","Synthetic closure"),p.passenger(),key()),404,"SUBSCRIPTION_NOT_FOUND");
        sql.update("UPDATE driver_profiles SET status='rejected' WHERE user_id=?",p.driver().id());String key=key();code(send("POST",path(p,"/remove"),Map.of("reason","Synthetic closure"),p.driver(),key),403,"DRIVER_NOT_APPROVED");sql.update("UPDATE driver_profiles SET status='approved' WHERE user_id=?",p.driver().id());ok(send("POST",path(p,"/remove"),Map.of("reason","Synthetic closure"),p.driver(),key));ended(p,p.legs(),p.total());
    }
    @Test void independentJavaInstancesReadAndMutationReplaysMatchInBothDirections() throws Exception {
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            for(boolean peerFirst:List.of(true,false)) {
                var p=paid();hold(p);String cancel=path(p,"/cancel-renewal").toUpperCase(Locale.ROOT).replace("/API/V1/SUBSCRIPTIONS/","/api/v1/subscriptions/").replace("/CANCEL-RENEWAL","/cancel-renewal"),cancelKey=key();
                if(peerFirst) { var old=peer.request("POST",cancel,Map.of(),headers(p.passenger(),cancelKey));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),ok(send("POST",cancel,Map.of(),p.passenger(),cancelKey))); }
                else { var body=ok(send("POST",cancel,Map.of(),p.passenger(),cancelKey));var old=peer.request("POST",cancel,Map.of(),headers(p.passenger(),cancelKey));assertEquals(200,old.path("status").asInt());assertEquals(body,old.path("body")); }
                for(String read:List.of(path(p,""),"/api/v1/subscriptions?side=driver&limit=1&offset=0")) assertEquals(peer.request("GET",read,null,headers(p.driver(),null)).path("body"),ok(send("GET",read,null,p.driver(),null)));
                String waitKey=key();var input=Map.of("routineId",p.routine().toString().toUpperCase(Locale.ROOT));
                if(peerFirst) { var old=peer.request("POST","/api/v1/waitlist",input,headers(p.passenger(),waitKey));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),ok(send("POST","/api/v1/waitlist",input,p.passenger(),waitKey))); }
                else { var body=ok(send("POST","/api/v1/waitlist",input,p.passenger(),waitKey));assertEquals(body,peer.request("POST","/api/v1/waitlist",input,headers(p.passenger(),waitKey)).path("body")); }
                String remove=path(p,"/remove"),removeKey=key();var reason=Map.of("reason","  Synthetic parity closure  ");
                if(peerFirst) { var old=peer.request("POST",remove,reason,headers(p.driver(),removeKey));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(old.path("body"),ok(send("POST",remove,reason,p.driver(),removeKey))); }
                else { var body=ok(send("POST",remove,reason,p.driver(),removeKey));var old=peer.request("POST",remove,reason,headers(p.driver(),removeKey));assertEquals(200,old.path("status").asInt(),old.path("body").path("code").asText());assertEquals(body,old.path("body")); }
                ended(p,p.legs(),p.total());assertEquals(peer.request("GET","/api/v1/waitlist",null,headers(p.passenger(),null)).path("body"),ok(send("GET","/api/v1/waitlist",null,p.passenger(),null)));
            }
        }
    }
    @Test void strictBodiesPaginationKeysAndOpenApiExposeTheOriginalContract() throws Exception {
        var driver=user(true);var passenger=user(false);UUID routine=publish(driver);
        for(String query:List.of("?side=admin","?limit=0","?offset=5001","?side=passenger&side=driver","?extra=1")) code(send("GET","/api/v1/subscriptions"+query,null,passenger,null),422,"VALIDATION_ERROR");
        code(send("POST","/api/v1/waitlist",Map.of("routineId",routine),passenger,null),422,"IDEMPOTENCY_REQUIRED");
        for(Object body:List.of(Map.of("routineId",routine,"extra",true),Map.of("routineId",42),Map.of("routineId","invalid"))) code(send("POST","/api/v1/waitlist",body,passenger,key()),422,"VALIDATION_ERROR");
        var p=paid(driver,passenger,routine);for(String reason:List.of("  "," a ","x".repeat(2001)," "+"x".repeat(2000))) code(send("POST",path(p,"/remove"),Map.of("reason",reason),driver,key()),422,"VALIDATION_ERROR");code(send("POST",path(p,"/cancel-renewal"),Map.of("extra",true),passenger,key()),422,"VALIDATION_ERROR");
        var api=ok(send("GET","/api/docs/json",null,null,null));assertTrue(api.path("paths").has("/api/v1/subscriptions/{id}/remove"));assertTrue(api.path("paths").has("/api/v1/waitlist/{id}/leave"));assertTrue(api.path("components").path("schemas").has("MonthlyContract"));assertTrue(api.path("paths").path("/api/v1/subscriptions").path("get").path("parameters").toString().contains("side"));
        var required=new HashSet<String>();api.path("paths").path("/api/v1/subscriptions/{id}/remove").path("post").path("parameters").forEach(parameter -> { if(parameter.path("required").asBoolean()) required.add(parameter.path("name").asText()); });assertTrue(required.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));
    }
    @Test void campusTimeDeterminesFutureWhileJvmZonesKeepCalendarDatesAndGraceTimestamp() throws Exception {
        var previous=TimeZone.getDefault();
        try {
            var p=paid();sql.update("UPDATE universities SET time_zone='Pacific/Kiritimati' WHERE id=?",university);sql.update("UPDATE subscriptions SET grace_until='2030-01-02T03:04:05.678Z' WHERE id=?",p.contract());
            Instant clock=sql.queryForObject("SELECT clock_timestamp()",(r,n) -> r.getTimestamp(1).toInstant());var campus=ZoneId.of("Pacific/Kiritimati");var pastTime=clock.minus(Duration.ofMinutes(30)).atZone(campus);var futureTime=clock.plus(Duration.ofMinutes(30)).atZone(campus);
            // Prefer an already-paid leg on the target date at a month boundary.
            UUID past=sql.queryForObject("SELECT rr.ride_id FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id WHERE rr.payment_id=? AND r.direction='ida' ORDER BY (r.ride_date=?::date) DESC,r.ride_date,r.id LIMIT 1",UUID.class,p.payment(),pastTime.toLocalDate().toString());UUID future=sql.queryForObject("SELECT rr.ride_id FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id WHERE rr.payment_id=? AND r.direction='volta' ORDER BY (r.ride_date=?::date) DESC,r.ride_date,r.id LIMIT 1",UUID.class,p.payment(),futureTime.toLocalDate().toString());
            // Clear only unbound synthetic materializations that would collide with the two moved legs.
            for(var target:List.of(Map.entry(past,pastTime),Map.entry(future,futureTime))) {
                String direction=target.getKey().equals(past)?"ida":"volta";
                sql.update("DELETE FROM rides r WHERE r.routine_id=? AND r.direction=? AND r.ride_date=?::date AND r.id<>? AND NOT EXISTS(SELECT 1 FROM ride_reservations rr WHERE rr.ride_id=r.id) AND NOT EXISTS(SELECT 1 FROM request_lines l WHERE l.ride_id=r.id) AND NOT EXISTS(SELECT 1 FROM subscription_schedule sc WHERE sc.ride_id=r.id)",p.routine(),direction,target.getValue().toLocalDate().toString(),target.getKey());
                sql.update("UPDATE rides SET ride_date=?::date,departure_time=?::time WHERE id=?",target.getValue().toLocalDate().toString(),target.getValue().toLocalTime().toString(),target.getKey());
            }
            assertEquals(p.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=?",p.payment()));
            JsonNode expected=null;for(String zone:List.of("UTC","Asia/Tokyo","America/Sao_Paulo")) { TimeZone.setDefault(TimeZone.getTimeZone(zone));var item=read(p,p.passenger());assertEquals(start,item.path("startDate").asText());assertEquals("2030-01-02T03:04:05.678Z",item.path("graceUntil").asText());if(expected!=null) assertEquals(expected,item);expected=item; }
            int retained=sql.queryForObject("SELECT cents FROM parcels WHERE reservation_id=(SELECT id FROM ride_reservations WHERE payment_id=? AND ride_id=?)",Integer.class,p.payment(),past);
            ok(send("POST",path(p,"/remove"),Map.of("reason","Synthetic campus boundary"),p.driver(),key()));ended(p,p.legs()-1,p.total()-retained);assertEquals("confirmed",sql.queryForObject("SELECT status FROM ride_reservations WHERE payment_id=? AND ride_id=?",String.class,p.payment(),past));assertEquals("cancelled",sql.queryForObject("SELECT status FROM ride_reservations WHERE payment_id=? AND ride_id=?",String.class,p.payment(),future));
        } finally { TimeZone.setDefault(previous);sql.update("UPDATE universities SET time_zone='America/Sao_Paulo' WHERE id=?",university); }
    }
    @Test void contractMutationsRequireAuthenticationOriginAndCsrfBeforeAnyFinancialChange() throws Exception {
        var p=paid();String path=path(p,"/remove");code(send("POST",path,Map.of("reason","Synthetic security"),null,key()),401,"UNAUTHENTICATED");
        for(boolean badOrigin:List.of(true,false)) {
            var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",badOrigin?"https://synthetic-other.example.test":AuthTestSettings.ORIGIN).header("Cookie",p.driver().cookie()).header("x-csrf-token",badOrigin?p.driver().csrf():"synthetic-invalid-csrf").header("idempotency-key",key()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("reason","Synthetic security"))));
            code(http.send(request.build(),HttpResponse.BodyHandlers.ofString()),403,badOrigin?"ORIGIN_INVALID":"CSRF_INVALID");
        }
        assertEquals("active",read(p,p.passenger()).path("status").asText());assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals(0,count("SELECT count(*) FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",p.contract()));assertEquals(0,ledger.reconciliation().difference());
    }
    private String reservation(UUID id,String action) { return "/api/v1/reservations/"+id+action; }
    private UUID firstReservation(Paid p) { return sql.queryForObject("SELECT id FROM ride_reservations WHERE payment_id=? ORDER BY id LIMIT 1",UUID.class,p.payment()); }
    @Test void reservationHistoryHasExactPaginationFiltersVehicleAndPassengerOnlyPin() throws Exception {
        var p=paid();var all=ok(send("GET","/api/v1/reservations?limit=100",null,p.passenger(),null));assertEquals(p.legs(),all.path("items").size());assertTrue(all.path("nextOffset").isNull());var item=all.path("items").get(0);assertEquals(p.contract().toString(),item.path("contractId").asText());assertTrue(item.path("pin").asText().matches("[0-9]{4}"));assertFalse(item.has("photo"));assertTrue(item.has("availability") && item.path("availability").isNull());assertTrue(item.path("deadline").isNull());assertEquals("",item.path("proposedTime").asText());assertEquals(3,item.path("vehicle").size());
        var page=ok(send("GET","/api/v1/reservations?limit=2&offset=1",null,p.passenger(),null));assertEquals(all.path("items").get(1),page.path("items").get(0));assertEquals(all.path("items").get(2),page.path("items").get(1));assertEquals(3,page.path("nextOffset").asInt());
        var upcoming=ok(send("GET","/api/v1/reservations?upcoming=true&limit=100",null,p.passenger(),null));assertEquals(p.legs(),upcoming.path("items").size());assertEquals(all.path("items").get(p.legs()-1),upcoming.path("items").get(0));
        var filtered=ok(send("GET","/api/v1/reservations?rideId="+item.path("rideId").asText()+"&contractId="+p.contract().toString().toUpperCase(Locale.ROOT),null,p.passenger(),null));assertEquals(1,filtered.path("items").size());assertEquals(item,filtered.path("items").get(0));assertTrue(ok(send("GET","/api/v1/reservations?contractId="+UUID.randomUUID(),null,p.passenger(),null)).path("items").isEmpty());
        var driver=ok(send("GET","/api/v1/reservations?side=driver&limit=100",null,p.driver(),null));assertEquals(p.legs(),driver.path("items").size());driver.path("items").forEach(row -> assertFalse(row.has("pin")));assertTrue(ok(send("GET","/api/v1/reservations",null,p.driver(),null)).path("items").isEmpty());var outsider=user(false);assertTrue(ok(send("GET","/api/v1/reservations",null,outsider,null)).path("items").isEmpty());code(send("GET",reservation(firstReservation(p),""),null,outsider,null),404,"RESERVATION_NOT_FOUND");
    }
    @Test void contactAndPhotoRespectBilateralBlockPurposeOwnerAndDeletionWithoutLeakingPinToDriver() throws Exception {
        var p=paid();UUID id=firstReservation(p);sql.update("UPDATE users SET phone='+5561999999999' WHERE id=?",p.driver().id());
        UUID photo=sql.queryForObject("INSERT INTO stored_files(owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,'photo',?,'image/webp',50,repeat('0',64)) RETURNING id",UUID.class,p.driver().id(),"synthetic-photo-"+UUID.randomUUID());sql.update("UPDATE users SET photo_file_id=? WHERE id=?",photo,p.driver().id());
        var detail=ok(send("GET",reservation(id,""),null,p.passenger(),null));assertEquals("/api/v1/files/"+photo,detail.path("photo").asText());assertFalse(ok(send("GET",reservation(id,""),null,p.driver(),null)).has("pin"));assertEquals("+5561999999999",ok(send("GET",reservation(id,"/contact"),null,p.passenger(),null)).path("phone").asText());assertEquals("",ok(send("GET",reservation(id,"/contact"),null,p.driver(),null)).path("phone").asText());
        for(boolean driverBlocks:List.of(true,false)) {
            UUID by=driverBlocks?p.driver().id():p.passenger().id(),other=driverBlocks?p.passenger().id():p.driver().id();sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",by,other);code(send("GET",reservation(id,"/contact"),null,p.passenger(),null),404,"CONTACT_UNAVAILABLE");code(send("GET",reservation(id,"/contact"),null,p.driver(),null),404,"CONTACT_UNAVAILABLE");var hidden=ok(send("GET",reservation(id,""),null,p.passenger(),null));assertEquals("",hidden.path("photo").asText());assertTrue(hidden.path("pin").asText().matches("[0-9]{4}"));sql.update("UPDATE user_blocks SET active=false WHERE by_id=? AND other_id=?",by,other);
        }
        sql.update("UPDATE stored_files SET deleted_at=now() WHERE id=?",photo);assertEquals("",ok(send("GET",reservation(id,""),null,p.passenger(),null)).path("photo").asText());
        for(String purpose:List.of("identity","photo")) { UUID owner=purpose.equals("photo")?p.passenger().id():p.driver().id();UUID wrong=sql.queryForObject("INSERT INTO stored_files(owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,?,'image/webp',50,repeat('0',64)) RETURNING id",UUID.class,owner,purpose,"synthetic-meta-"+UUID.randomUUID());sql.update("UPDATE users SET photo_file_id=? WHERE id=?",wrong,p.driver().id());assertEquals("",ok(send("GET",reservation(id,""),null,p.passenger(),null)).path("photo").asText()); }
    }
    @Test void reservationReadStatesKeepNullableFieldsAndSuppressSensitiveFieldsWhenClosed() throws Exception {
        var p=paid();var rows=sql.queryForList("SELECT rr.id,p.id AS parcel FROM ride_reservations rr JOIN parcels p ON p.reservation_id=rr.id WHERE rr.payment_id=? ORDER BY rr.id",p.payment());
        var statuses=List.of("boarded","awaiting_confirmation","completed","cancelled","under_review");for(int index=0;index<statuses.size();index++) {
            var row=rows.get(index);String state=statuses.get(index);UUID id=(UUID)row.get("id"),parcel=(UUID)row.get("parcel");
            tx.executeWithoutResult(ignored -> {
                if(state.equals("completed")) { app.getBean(ParcelService.class).settle(parcel,ParcelService.Outcome.RELEASED,"Synthetic read completed",false);sql.update("UPDATE ride_reservations SET status='awaiting_confirmation',availability='fulfilled',confirmation_deadline='2030-01-02T03:04:05.678Z' WHERE id=?",id); }
                if(state.equals("cancelled")) app.getBean(ParcelService.class).settle(parcel,ParcelService.Outcome.CREDITED,"Synthetic read cancelled",false);
                if(state.equals("under_review")) app.getBean(ParcelService.class).block(parcel,"Synthetic read review");
                if(state.equals("awaiting_confirmation")) sql.update("UPDATE parcels SET status='awaiting' WHERE id=?",parcel);
                if(state.equals("boarded")) sql.update("UPDATE ride_reservations SET status='boarded',boarded_at=now() WHERE id=?",id); else sql.update("UPDATE ride_reservations SET status=? WHERE id=?",state,id);
            });
            var detail=ok(send("GET",reservation(id,""),null,p.passenger(),null));assertEquals(state,detail.path("status").asText());assertFalse(detail.has("pin"),"Consumed and closed reservation states must not expose a boarding credential.");assertEquals("",detail.path("photo").asText());assertEquals(state.equals("boarded")?"ABC1D23":"Placa protegida",detail.path("vehicle").path("plate").asText());if(state.equals("completed")) assertEquals("2030-01-02T03:04:05.678Z",detail.path("deadline").asText());
            var contact=send("GET",reservation(id,"/contact"),null,p.passenger(),null);if(!state.equals("boarded")) code(contact,404,"CONTACT_UNAVAILABLE");else ok(contact);
        }
        assertEquals(p.legs()-4,ok(send("GET","/api/v1/reservations?upcoming=true&limit=100",null,p.passenger(),null)).path("items").size());assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void reservationHistoryRemainsAvailableToRestrictedAccountsAndDatesIgnoreJvmZone() throws Exception {
        var p=paid();UUID id=firstReservation(p);var expected=ok(send("GET",reservation(id,""),null,p.passenger(),null));var zone=TimeZone.getDefault();
        try { sql.update("UPDATE users SET account_status='suspended' WHERE id=?",p.passenger().id());for(String name:List.of("UTC","Asia/Tokyo","America/Sao_Paulo")) { TimeZone.setDefault(TimeZone.getTimeZone(name));assertEquals(expected,ok(send("GET",reservation(id,""),null,p.passenger(),null)));ok(send("GET","/api/v1/reservations",null,p.passenger(),null));ok(send("GET",reservation(id,"/contact"),null,p.passenger(),null)); } }
        finally { TimeZone.setDefault(zone); }
    }
    @Test void finishedRideHidesPeerFieldsInDetailListAndOfferEvenBeforeReservationReconciliation() throws Exception {
        var p=paid();UUID id=firstReservation(p),ride=sql.queryForObject("SELECT ride_id FROM ride_reservations WHERE id=?",UUID.class,id);
        UUID photo=sql.queryForObject("INSERT INTO stored_files(owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,'photo',?,'image/webp',50,repeat('0',64)) RETURNING id",UUID.class,p.driver().id(),"synthetic-closed-photo-"+UUID.randomUUID());
        sql.update("UPDATE users SET photo_file_id=?,phone='+5561999999999' WHERE id=?",photo,p.driver().id());
        assertEquals("/api/v1/files/"+photo,ok(send("GET",reservation(id,""),null,p.passenger(),null)).path("photo").asText());
        sql.update("UPDATE rides SET status='active' WHERE id=?",ride);sql.update("UPDATE rides SET status='completed' WHERE id=?",ride);
        assertEquals("confirmed",sql.queryForObject("SELECT status FROM ride_reservations WHERE id=?",String.class,id));
        var detail=ok(send("GET",reservation(id,""),null,p.passenger(),null));assertEquals("",detail.path("photo").asText());assertEquals("Placa protegida",detail.path("vehicle").path("plate").asText());assertFalse(detail.has("pin"));
        var rows=ok(send("GET","/api/v1/reservations?rideId="+ride,null,p.passenger(),null)).path("items");assertEquals(1,rows.size());assertEquals("Placa protegida",rows.get(0).path("vehicle").path("plate").asText());
        assertEquals("Placa protegida",ok(send("GET","/api/v1/rides/"+ride,null,p.passenger(),null)).path("vehicle").path("plate").asText());
        assertEquals("ABC1D23",ok(send("GET",reservation(id,""),null,p.driver(),null)).path("vehicle").path("plate").asText());
        code(send("GET",reservation(id,"/contact"),null,p.passenger(),null),404,"CONTACT_UNAVAILABLE");code(send("GET",reservation(id,"/contact"),null,p.driver(),null),404,"CONTACT_UNAVAILABLE");
        assertEquals(p.legs(),count("SELECT count(*) FROM parcels WHERE payment_id=?",p.payment()));assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void reservationReadsPreserveContractsAndCanonicalUuidComparison() throws Exception {
        var p=paid();UUID id=firstReservation(p);var passenger=p.passenger();var driver=p.driver();
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            for(Session actor:List.of(passenger,driver)) for(String path:List.of(reservation(id,""),reservation(id,"/contact"),"/api/v1/reservations?side="+(actor==driver?"driver":"passenger")+"&limit=2&offset=1","/api/v1/reservations?upcoming=true&contractId="+p.contract()+"&rideId="+sql.queryForObject("SELECT ride_id FROM ride_reservations WHERE id=?",UUID.class,id))) {
                var old=peer.request("GET",path,null,headers(actor,null));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),ok(send("GET",path,null,actor,null)));
            }
            String uppercase="/api/v1/reservations/"+id.toString().toUpperCase(Locale.ROOT);var uppercaseRead=peer.request("GET",uppercase,null,headers(passenger,null));assertEquals(200,uppercaseRead.path("status").asInt());assertEquals(ok(send("GET",reservation(id,""),null,passenger,null)),uppercaseRead.path("body"));assertEquals(ok(send("GET",reservation(id,""),null,passenger,null)),ok(send("GET",uppercase,null,passenger,null)));
            ok(send("POST",path(p,"/remove"),Map.of("reason","Synthetic read parity cancellation"),driver,key()));
            // Intentional privacy change: cancellation keeps the contract history but no longer discloses a plate.
            var old=peer.request("GET",reservation(id,""),null,headers(passenger,null)).path("body");
            assertEquals("Placa protegida",old.path("vehicle").path("plate").asText());
            assertEquals("Detalhes do encontro protegidos",old.path("meeting").asText());
            assertEquals(old,ok(send("GET",reservation(id,""),null,passenger,null)));code(send("GET",reservation(id,"/contact"),null,passenger,null),404,"CONTACT_UNAVAILABLE");
        }
    }
    @Test void reservationQueriesRejectUnknownDuplicateAndInvalidFiltersAndDocumentTheirParameters() throws Exception {
        var actor=user(false);for(String query:List.of("?side=admin","?rideId=invalid","?contractId=invalid","?upcoming=1","?upcoming=TRUE","?rideId=","?limit=2&limit=3","?extra=1")) code(send("GET","/api/v1/reservations"+query,null,actor,null),422,"VALIDATION_ERROR");code(send("GET","/api/v1/reservations",null,null,null),401,"UNAUTHENTICATED");code(send("GET","/api/v1/reservations/invalid/contact",null,actor,null),422,"VALIDATION_ERROR");code(send("GET",reservation(UUID.randomUUID(),"/contact"),null,actor,null),404,"CONTACT_UNAVAILABLE");
        var api=ok(send("GET","/api/docs/json",null,null,null));var names=new HashSet<String>();api.path("paths").path("/api/v1/reservations").path("get").path("parameters").forEach(parameter -> names.add(parameter.path("name").asText()));assertTrue(names.containsAll(Set.of("side","rideId","contractId","upcoming","limit","offset")));assertTrue(api.path("paths").has("/api/v1/reservations/{id}/contact"));assertTrue(api.path("components").path("schemas").has("Reservation"));
    }
    @Test void avulsaHistoryHasEmptyContractAndHidesPinOnceTheRideIsClosed() throws Exception {
        var driver=user(true);var passenger=user(false);var offer=Map.of("region","Gama","meeting","Synthetic avulsa stop","direction","ida","date",start,"time","18:30","capacity",2,"distance",10,"automatic",true);
        UUID ride=UUID.fromString(ok(send("POST","/api/v1/rides",offer,driver,key())).path("id").asText());UUID request=UUID.fromString(ok(send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",ride),passenger,key())).path("id").asText());UUID payment=sql.queryForObject("SELECT id FROM payments WHERE request_id=?",UUID.class,request);int cents=sql.queryForObject("SELECT amount_cents FROM payments WHERE id=?",Integer.class,payment);
        tx.executeWithoutResult(status -> ledger.post(LedgerCommand.transfer("synthetic-funding-"+UUID.randomUUID(),"synthetic_funding","Synthetic avulsa read fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-cents),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,cents)))));
        assertTrue(ok(send("POST","/api/v1/payments/"+payment+"/checkout",Map.of("method","pix","useCredit",true),passenger,key())).path("confirmed").asBoolean());UUID id=sql.queryForObject("SELECT id FROM ride_reservations WHERE payment_id=?",UUID.class,payment);var detail=ok(send("GET",reservation(id,""),null,passenger,null));assertEquals("",detail.path("contractId").asText());assertTrue(detail.path("pin").asText().matches("[0-9]{4}"));
        sql.update("UPDATE rides SET status='active' WHERE id=?",ride);assertTrue(ok(send("GET",reservation(id,""),null,passenger,null)).has("pin"));sql.update("UPDATE rides SET status='completed' WHERE id=?",ride);assertFalse(ok(send("GET",reservation(id,""),null,passenger,null)).has("pin"));assertTrue(ok(send("GET","/api/v1/reservations?upcoming=true",null,passenger,null)).path("items").isEmpty());assertEquals(1,ok(send("GET","/api/v1/reservations",null,passenger,null)).path("items").size());assertEquals(0,ledger.reconciliation().difference());
    }
    @Test void closureWithNoEligibleFutureReservationReleasesHoldsWithoutFinancialMovement() throws Exception {
        var p=paid();hold(p);int journals=count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",p.payment());sql.update("UPDATE rides SET status='active' WHERE id IN (SELECT ride_id FROM ride_reservations WHERE payment_id=?)",p.payment());
        ok(send("POST",path(p,"/remove"),Map.of("reason","Synthetic started commitments"),p.driver(),key()));ended(p,0,0);assertEquals(p.total(),ledger.balance(p.passenger().id(),AccountKind.FUTURE));assertEquals(p.legs(),count("SELECT count(*) FROM ride_reservations WHERE payment_id=? AND status='confirmed'",p.payment()));assertEquals(journals,count("SELECT count(*) FROM ledger_transactions WHERE payment_id=?",p.payment()));assertEquals(0,read(p,p.passenger()).path("heldLegs").asInt());assertEquals(0,sql.queryForObject("SELECT (metadata->>'affected')::int FROM audit_logs WHERE action='subscription_future_closed' AND resource_id=?",Integer.class,p.contract()));
    }
}
