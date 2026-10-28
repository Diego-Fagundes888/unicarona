package com.unicarona.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.payment.service.ParcelService.Outcome;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.TransactionRetries;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class LedgerHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static LedgerService ledger;
    private static ParcelService parcels;
    private static TransactionTemplate tx;
    private static TransactionRetries retries;
    private static String server,passwordHash;
    private static UUID university,course,classId;
    private static final String PASSWORD="Synthetic ledger HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie) { @Override public String toString() { return "Session[redacted]"; } }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        sql=app.getBean(JdbcTemplate.class);ledger=app.getBean(LedgerService.class);parcels=app.getBean(ParcelService.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));retries=app.getBean(TransactionRetries.class);
        server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('ledger-university','Ledger University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'ledger-course','Ledger Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,1,'A') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void limits() { sql.update("DELETE FROM rate_limits"); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private HttpResponse<String> get(String path,Session user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path));if(user!=null) request.header("Cookie",user.cookie());return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private Session user(boolean admin) throws Exception {
        String email="ledger-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,role,email_verified_at) VALUES(?,?,'Synthetic Ledger','2000-05-01',?,now()) RETURNING id",UUID.class,email,passwordHash,admin?"admin":"user");
        if(!admin) sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',1,'night','Gama')",id,university,course,classId);
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/auth/login")).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email",email,"password",PASSWORD)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
        return new Session(id,String.join("; ",response.headers().allValues("set-cookie").stream().map(header -> header.split(";",2)[0]).toList()));
    }
    private String key() { return "synthetic-ledger-"+UUID.randomUUID(); }
    private LedgerCommand credit(UUID owner,long amount,String key) {
        return LedgerCommand.transfer(key,"synthetic_funding","Fixture only",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-amount),new Posting(owner,AccountKind.PASSENGER_CREDIT,amount)));
    }
    private UUID post(LedgerCommand command) { return retries.call(() -> tx.execute(status -> ledger.post(command))); }
    private Object referenceBody(LedgerCommand command) { var input=new LinkedHashMap<String,Object>();input.put("key",command.key());input.putAll(command.fingerprintBody());return input; }
    @Test void independentJavaInstancesJournalFingerprintsReplayInBothDirections() throws Exception {
        var user=user(false);var first=credit(user.id(),1200,key());
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var inserted=peer.journal(referenceBody(first));assertEquals(200,inserted.path("status").asInt(),() -> inserted.path("body").path("code").asText());
            assertEquals(inserted.path("body").path("id").asText(),post(first).toString());
            var second=credit(user.id(),800,key());UUID id=post(second);var replay=peer.journal(referenceBody(second));assertEquals(200,replay.path("status").asInt(),() -> replay.path("body").path("code").asText());assertEquals(id.toString(),replay.path("body").path("id").asText());
            assertEquals(2000,ledger.balance(user.id(),AccountKind.PASSENGER_CREDIT));
            var changed=LedgerCommand.transfer(first.key(),first.kind(),"Different reason",first.entries());
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiException.class,() -> post(changed)).code());
            assertEquals("IDEMPOTENCY_CONFLICT",peer.journal(referenceBody(changed)).path("body").path("code").asText());
            assertEquals(peer.request("GET","/api/v1/wallet",null,Map.of("cookie",user.cookie())).path("body"),tree(get("/api/v1/wallet",user)));
        }
    }
    @Test void concurrentLastBalanceCanBeSpentOnlyOnce() throws Exception {
        var user=user(false);post(credit(user.id(),1000,key()));
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> spend=() -> {
                try { post(LedgerCommand.transfer(key(),"synthetic_spend","Fixture only",List.of(new Posting(user.id(),AccountKind.PASSENGER_CREDIT,-700),new Posting(user.id(),AccountKind.DRIVER_AVAILABLE,700))));return "success"; }
                catch(ApiException error) { return error.code(); }
            };
            var a=executor.submit(spend);var b=executor.submit(spend);assertEquals(Set.of("success","INSUFFICIENT_BALANCE"),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
        }
        assertEquals(300,ledger.balance(user.id(),AccountKind.PASSENGER_CREDIT));assertEquals(700,ledger.balance(user.id(),AccountKind.DRIVER_AVAILABLE));
    }
    @Test void concurrentReplayCreatesOneBalancedJournal() throws Exception {
        var user=user(false);var command=credit(user.id(),900,key());
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> post(command));var second=executor.submit(() -> post(command));assertEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(900,ledger.balance(user.id(),AccountKind.PASSENGER_CREDIT));
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.event_key=?",Integer.class,command.key()));
        assertEquals(0L,sql.queryForObject("SELECT sum(e.cents)::bigint FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.event_key=?",Long.class,command.key()));
    }
    @Test void invalidEntriesFailAndJournalRequiresAnOuterTransaction() throws Exception {
        var user=user(false);var valid=credit(user.id(),100,key());assertThrows(IllegalTransactionStateException.class,() -> ledger.post(valid));
        var imbalance=LedgerCommand.transfer(key(),"synthetic","Fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-100),new Posting(user.id(),AccountKind.PASSENGER_CREDIT,101)));
        assertEquals("LEDGER_UNBALANCED",assertThrows(ApiException.class,() -> post(imbalance)).code());
        var zero=LedgerCommand.transfer(key(),"synthetic","Fixture",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,0),new Posting(user.id(),AccountKind.PASSENGER_CREDIT,0)));
        assertEquals("LEDGER_UNBALANCED",assertThrows(ApiException.class,() -> post(zero)).code());
        var overflow=credit(user.id(),Amounts.MAXIMUM_SAFE+1,key());assertEquals("LEDGER_UNBALANCED",assertThrows(ApiException.class,() -> post(overflow)).code());
        assertEquals(0,ledger.balance(user.id(),AccountKind.PASSENGER_CREDIT));assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_accounts WHERE owner_id=?",Integer.class,user.id()));
    }
    @Test void walletIsOwnAndAvailableForRestrictedAccountsAndReconciliationIsAdminOnly() throws Exception {
        var user=user(false);var other=user(false);var admin=user(true);post(credit(user.id(),1300,key()));
        assertEquals(401,get("/api/v1/wallet",null).statusCode());assertEquals(1300,tree(get("/api/v1/wallet",user)).path("credit").asLong());assertEquals(0,tree(get("/api/v1/wallet",other)).path("credit").asLong());
        assertEquals(403,get("/api/v1/admin/reconciliation",user).statusCode());sql.update("UPDATE users SET account_status='suspended' WHERE id=?",user.id());
        assertEquals(200,get("/api/v1/wallet",user).statusCode());var reconciliation=tree(get("/api/v1/admin/reconciliation",admin));assertEquals(0,reconciliation.path("difference").asLong());
        assertEquals(reconciliation.path("received"),reconciliation.path("allocated"));
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { assertEquals(peer.request("GET","/api/v1/admin/reconciliation",null,Map.of("cookie",admin.cookie())).path("body"),reconciliation); }
    }
    @Test void balanceOverflowFailsInsteadOfReturningRoundedMoneyAndHistoryRemainsImmutable() throws Exception {
        var user=user(false);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();ledger.post(credit(user.id(),Amounts.MAXIMUM_SAFE,key()));
            assertEquals(Amounts.MAXIMUM_SAFE,ledger.balance(user.id(),AccountKind.PASSENGER_CREDIT));
            assertEquals("AMOUNT_OVERFLOW",assertThrows(ApiException.class,() -> ledger.post(credit(user.id(),1,key()))).code());
            assertEquals(Amounts.MAXIMUM_SAFE,ledger.balance(user.id(),AccountKind.PASSENGER_CREDIT));
        });
        UUID header=post(credit(user.id(),100,key()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> sql.update("UPDATE ledger_transactions SET reason='Changed fixture' WHERE id=?",header));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> sql.update("DELETE FROM ledger_entries WHERE transaction_id=?",header));
    }
    @Test void parcelAmountsAppearForBothParticipantsAndHoldUsesCompatibleJournalFingerprint() throws Exception {
        var passenger=user(false);var driver=user(false);UUID[] contract=paidParcel(passenger,driver);
        assertEquals(400,tree(get("/api/v1/wallet",passenger)).path("future").asInt());assertEquals(400,tree(get("/api/v1/wallet",driver)).path("future").asInt());
        var hold=LedgerCommand.block(contract[1],contract[0],"Synthetic dispute",List.of(new Posting(passenger.id(),AccountKind.FUTURE,-400),new Posting(passenger.id(),AccountKind.BLOCKED,400)));
        tx.executeWithoutResult(status -> { ledger.post(hold);sql.update("UPDATE parcels SET status='blocked',reason='Synthetic dispute' WHERE id=?",contract[1]); });
        var wallet=tree(get("/api/v1/wallet",driver));assertEquals(0,wallet.path("future").asInt());assertEquals(400,wallet.path("blocked").asInt());
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var replay=peer.journal(referenceBody(hold));assertEquals(200,replay.path("status").asInt());assertEquals(post(hold).toString(),replay.path("body").path("id").asText());
            assertEquals(peer.request("GET","/api/v1/wallet",null,Map.of("cookie",driver.cookie())).path("body"),wallet);
        }
    }
    private UUID[] paidParcel(Session passenger,Session driver) {
        return paidParcel(passenger,driver,false);
    }
    private UUID[] paidParcel(Session passenger,Session driver,boolean unavailable) {
        return tx.execute(status -> {
            UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Fixture','Blue','ABC1D23',4,'approved') RETURNING id",UUID.class,driver.id());
            UUID pricing=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000) RETURNING id",UUID.class,university);
            UUID route=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Gama','Campus',10000) RETURNING id",UUID.class,university,driver.id());
            UUID ride=sql.queryForObject("INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,'ida',current_date+1,'18:00',4,400,1000,?) RETURNING id",UUID.class,university,route,driver.id(),vehicle,pricing);
            UUID request=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,expires_at) VALUES(?,?,?,'avulsa','accepted',now()+interval '1 day') RETURNING id",UUID.class,passenger.id(),driver.id(),ride);
            sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,400,1000)",request,ride);
            UUID payment=sql.queryForObject("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at) VALUES(?,?,400,'development','awaiting',now()+interval '1 day') RETURNING id",UUID.class,request,passenger.id());
            sql.update("UPDATE payments SET status='pending',external_cents=400 WHERE id=?",payment);
            if(unavailable) {
                ledger.post(LedgerCommand.receiptCredit(payment,"Vaga indisponível após confirmação do provedor",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-400),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,400))));
                sql.update("UPDATE payments SET status='approved',paid_at=now() WHERE id=?",payment);sql.update("UPDATE ride_requests SET status='cancelled' WHERE id=?",request);return new UUID[]{payment,null};
            }
            ledger.post(LedgerCommand.receipt(payment,"Pagamento confirmado; parcelas futuras",List.of(new Posting(passenger.id(),AccountKind.FUTURE,400),new Posting(null,AccountKind.PROVIDER_CLEARING,-400))));
            UUID reservation=sql.queryForObject("INSERT INTO ride_reservations(request_id,ride_id,passenger_id,driver_id,payment_id,status,pin_ciphertext,pin_hash,price_cents) VALUES(?,?,?,?,?,'confirmed','synthetic-cipher','synthetic-hash',400) RETURNING id",UUID.class,request,ride,passenger.id(),driver.id(),payment);
            UUID parcel=sql.queryForObject("INSERT INTO parcels(reservation_id,payment_id,passenger_id,driver_id,cents,commission_bps,status) VALUES(?,?,?,?,400,1000,'future') RETURNING id",UUID.class,reservation,payment,passenger.id(),driver.id());
            sql.update("UPDATE payments SET status='approved',paid_at=now() WHERE id=?",payment);sql.update("UPDATE ride_requests SET status='confirmed' WHERE id=?",request);
            return new UUID[]{payment,parcel};
        });
    }
    @Test void financialOutcomesPreserveTheContractAndReplayAllOriginalFingerprintLayouts() throws Exception {
        var passenger=user(false);var driver=user(false);UUID[] contract=paidParcel(passenger,driver);String reason="Synthetic release";
        tx.executeWithoutResult(status -> parcels.settle(contract[1],Outcome.RELEASED,reason,false));
        assertEquals(360,ledger.balance(driver.id(),AccountKind.DRIVER_AVAILABLE));assertEquals("released",sql.queryForObject("SELECT status FROM parcels WHERE id=?",String.class,contract[1]));
        assertEquals(40,sql.queryForObject("SELECT commission_cents FROM parcels WHERE id=?",Integer.class,contract[1]));
        tx.executeWithoutResult(status -> parcels.settle(contract[1],Outcome.RELEASED,reason,false));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"parcel:"+contract[1]+":settled"));
        assertEquals("PARCEL_SETTLED",assertThrows(ApiException.class,() -> tx.executeWithoutResult(status -> parcels.settle(contract[1],Outcome.CREDITED,"Changed",true))).code());
        var settled=LedgerCommand.settle(contract[1],contract[0],"released",reason,List.of(new Posting(passenger.id(),AccountKind.FUTURE,-400),new Posting(driver.id(),AccountKind.DRIVER_AVAILABLE,360),new Posting(null,AccountKind.COMMISSION,40)));
        var receipt=LedgerCommand.receipt(contract[0],"Pagamento confirmado; parcelas futuras",List.of(new Posting(passenger.id(),AccountKind.FUTURE,400),new Posting(null,AccountKind.PROVIDER_CLEARING,-400)));
        UUID[] late=paidParcel(passenger,user(false),true);var credit=LedgerCommand.receiptCredit(late[0],"Vaga indisponível após confirmação do provedor",List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,-400),new Posting(passenger.id(),AccountKind.PASSENGER_CREDIT,400)));
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            for(var command:List.of(settled,receipt,credit)) {
                var old=peer.journal(referenceBody(command));assertEquals(200,old.path("status").asInt());assertEquals(post(command).toString(),old.path("body").path("id").asText());
            }
        }
    }
    @Test void blockedAndAwaitingParcelsRequireReviewAndCanBecomeCreditOrRetained() throws Exception {
        for(var outcome:List.of(Outcome.CREDITED,Outcome.RETAINED)) {
            var passenger=user(false);var driver=user(false);UUID[] contract=paidParcel(passenger,driver);
            sql.update("UPDATE parcels SET status='awaiting' WHERE id=?",contract[1]);
            tx.executeWithoutResult(status -> parcels.block(contract[1],"Synthetic hold"));tx.executeWithoutResult(status -> parcels.block(contract[1],"Repeated hold"));
            assertEquals(400,ledger.balance(passenger.id(),AccountKind.BLOCKED));
            assertEquals("PARCEL_BLOCKED",assertThrows(ApiException.class,() -> tx.executeWithoutResult(status -> parcels.settle(contract[1],outcome,"Review needed",false))).code());
            tx.executeWithoutResult(status -> parcels.settle(contract[1],outcome,"Synthetic reviewed decision",true));
            assertEquals(0,ledger.balance(passenger.id(),AccountKind.BLOCKED));assertEquals(outcome.value(),sql.queryForObject("SELECT status FROM parcels WHERE id=?",String.class,contract[1]));
            assertEquals(outcome==Outcome.CREDITED?400:0,ledger.balance(passenger.id(),AccountKind.PASSENGER_CREDIT));
            assertEquals(0,sql.queryForObject("SELECT commission_cents+driver_cents FROM parcels WHERE id=?",Integer.class,contract[1]));
            assertNotNull(sql.queryForObject("SELECT settled_at FROM parcels WHERE id=?",java.sql.Timestamp.class,contract[1]));
        }
    }
    @Test void concurrentOppositeParcelDecisionsSettleExactlyOnce() throws Exception {
        var passenger=user(false);var driver=user(false);UUID[] contract=paidParcel(passenger,driver);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> release=() -> decision(contract[1],Outcome.RELEASED);Callable<String> credit=() -> decision(contract[1],Outcome.CREDITED);
            var a=executor.submit(release);var b=executor.submit(credit);assertEquals(Set.of("success","PARCEL_SETTLED"),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions WHERE event_key=?",Integer.class,"parcel:"+contract[1]+":settled"));
        assertEquals(0,ledger.balance(passenger.id(),AccountKind.FUTURE));
    }
    private String decision(UUID parcel,Outcome outcome) {
        try { retries.run(() -> tx.executeWithoutResult(status -> parcels.settle(parcel,outcome,"Synthetic concurrent decision",false)));return "success"; }
        catch(ApiException error) { return error.code(); }
    }
}
