package com.unicarona.ride;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.privacy.service.PrivacyLocationRetentionTransactions;
import com.unicarona.privacy.service.PrivacyReadiness;
import com.unicarona.privacy.service.PrivacyRestoreGuard;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.URI;
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


class RideHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static TransactionTemplate tx;
    private static LedgerService ledger;
    private static UUID university,course,classId,pricing;
    private static String server,passwordHash,date;
    private static final String PASSWORD="Synthetic ride HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        sql=app.getBean(JdbcTemplate.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));ledger=app.getBean(LedgerService.class);
        server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);date=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('ride-university','Ride University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'ride-course','Ride Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,university,course);
        pricing=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000) RETURNING id",UUID.class,university);
    }
    @BeforeEach void limits() { sql.update("DELETE FROM rate_limits"); }
    @AfterAll static void close() { if(app!=null) app.close();if(database!=null) database.close(); }
    private Session user(boolean driver) throws Exception {
        String email="ride-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic Driver','2000-05-01',now()) RETURNING id",UUID.class,email,passwordHash);
        com.unicarona.support.LegalTestFixtures.acceptedBusinessUser(app,id);
        sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',3,'night','Gama')",id,university,course,classId);
        if(driver) {
            sql.update("INSERT INTO driver_profiles(user_id,status,approved_at) VALUES(?,'approved',now())",id);
            sql.update("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic Car','Black','ABC1D23',4,'approved')",id);
        }
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")) { var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]); }
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private HttpResponse<String> send(String method,String path,Object body,Session user,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(user!=null) request.header("Cookie",user.cookie()).header("x-csrf-token",user.csrf());if(key!=null) request.header("idempotency-key",key);
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else request.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void code(HttpResponse<String> response,int status,String code) throws Exception { assertEquals(status,response.statusCode());assertEquals(code,tree(response).path("code").asText()); }
    private Map<String,String> headers(Session user,String key) { var result=new HashMap<String,String>();result.put("cookie",user.cookie());result.put("origin",AuthTestSettings.ORIGIN);result.put("x-csrf-token",user.csrf());if(key!=null) result.put("idempotency-key",key);return result; }
    private String key() { return "synthetic-ride-"+UUID.randomUUID(); }
    private Map<String,Object> offer() {
        var input=new LinkedHashMap<String,Object>();input.put("region","  Gáma  ");input.put("meeting","  Synthetic bus stop  ");input.put("direction","ida");input.put("date",date);input.put("time","18:30");input.put("capacity",4);
        input.put("distance",10.0);input.put("automatic",true);return input;
    }
    private Map<String,Object> edit(int capacity,double distance) { var input=new LinkedHashMap<String,Object>();input.put("capacity",capacity);input.put("distance",distance);input.put("automatic",false);return input; }
    private UUID publish(Session driver,Map<String,Object> input) throws Exception { var response=send("POST","/api/v1/rides",input,driver,key());assertEquals(200,response.statusCode());return UUID.fromString(tree(response).path("id").asText()); }
    private String search(Session driver) { return "/api/v1/rides?date="+date+"&driverId="+driver.id(); }
    private UUID routine(Session driver,UUID ride) {
        UUID route=sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,ride),vehicle=sql.queryForObject("SELECT vehicle_id FROM rides WHERE id=?",UUID.class,ride);
        UUID routine=sql.queryForObject("INSERT INTO routines(route_id,driver_id,university_id,vehicle_id,class_id,weekdays,ida_time,ida_cents,capacity,pricing_version_id) VALUES(?,?,?,?,?,ARRAY[1,2]::smallint[],'18:30',400,4,?) RETURNING id",UUID.class,route,driver.id(),university,vehicle,classId,pricing);
        sql.update("UPDATE rides SET routine_id=? WHERE id=?",routine,ride);return routine;
    }
    private UUID hold(Session driver,Session passenger,UUID routine,UUID ride) {
        UUID subscription=sql.queryForObject("INSERT INTO subscriptions(routine_id,driver_id,passenger_id,class_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status) VALUES(?,?,?,?,current_date,current_date+30,ARRAY[1,2]::smallint[],'ida',400,1000,'active') RETURNING id",UUID.class,routine,driver.id(),passenger.id(),classId);
        sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",subscription,ride);return subscription;
    }
    private UUID[] contract(Session driver,Session passenger,UUID ride) {
        return tx.execute(status -> {
            UUID request=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,expires_at) VALUES(?,?,?,'avulsa','accepted',now()+interval '1 day') RETURNING id",UUID.class,passenger.id(),driver.id(),ride);
            sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,400,1000)",request,ride);
            UUID payment=sql.queryForObject("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at) VALUES(?,?,400,'development','awaiting',now()+interval '1 day') RETURNING id",UUID.class,request,passenger.id());
            sql.update("UPDATE payments SET status='pending',external_cents=400 WHERE id=?",payment);
            ledger.post(LedgerCommand.receipt(payment,"Synthetic ride contract",List.of(new Posting(passenger.id(),AccountKind.FUTURE,400),new Posting(null,AccountKind.PROVIDER_CLEARING,-400))));
            UUID reservation=sql.queryForObject("INSERT INTO ride_reservations(request_id,ride_id,passenger_id,driver_id,payment_id,status,pin_ciphertext,pin_hash,price_cents) VALUES(?,?,?,?,?,'confirmed','synthetic-cipher','synthetic-hash',400) RETURNING id",UUID.class,request,ride,passenger.id(),driver.id(),payment);
            UUID parcel=sql.queryForObject("INSERT INTO parcels(reservation_id,payment_id,passenger_id,driver_id,cents,commission_bps,status) VALUES(?,?,?,?,400,1000,'future') RETURNING id",UUID.class,reservation,payment,passenger.id(),driver.id());
            sql.update("UPDATE payments SET status='approved',paid_at=now() WHERE id=?",payment);sql.update("UPDATE ride_requests SET status='confirmed' WHERE id=?",request);return new UUID[]{request,reservation,parcel,payment};
        });
    }
    @Test void publishCalculatesPriceAndReplaysNormalizedInputWithoutCollectingUnusedLocation() throws Exception {
        var driver=user(true);var passenger=user(false);var input=offer();input.put("distance",10.1235);input.put("toll","2.50");input.put("notes","  Synthetic note \u00a0");
        String key=key();var response=send("POST","/api/v1/rides",input,driver,key);assertEquals(200,response.statusCode());UUID ride=UUID.fromString(tree(response).path("id").asText());
        assertEquals(tree(response),tree(send("POST","/api/v1/rides",input,driver,key)));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rides WHERE driver_id=?",Integer.class,driver.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='ride_published' AND resource_id=?",Integer.class,ride));
        assertEquals(10124,sql.queryForObject("SELECT rt.distance_m FROM rides r JOIN ride_routes rt ON rt.id=r.route_id WHERE r.id=?",Integer.class,ride));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM rides r JOIN ride_routes rt ON rt.id=r.route_id WHERE r.id=? AND (rt.origin_address IS NOT NULL OR rt.destination_address IS NOT NULL OR rt.origin_lat IS NOT NULL OR rt.origin_lng IS NOT NULL OR rt.destination_lat IS NOT NULL OR rt.destination_lng IS NOT NULL)",Integer.class,ride));
        var detail=tree(send("GET","/api/v1/rides/"+ride,null,passenger,null));assertEquals("Gáma",detail.path("region").asText());assertEquals("",detail.path("notes").asText());assertEquals("Disponível após confirmação",detail.path("meeting").asText());assertEquals("18:30",detail.path("time").asText());assertEquals("",detail.path("driver").path("classLabel").asText());assertEquals("protected",detail.path("driver").path("periodStatus").asText());
        assertEquals("Synthetic note",tree(send("GET","/api/v1/rides/"+ride,null,driver,null)).path("notes").asText());
        assertEquals("Disponível após confirmação",detail.path("vehicle").path("plate").asText());assertEquals("ABC1D23",tree(send("GET","/api/v1/rides/"+ride,null,driver,null)).path("vehicle").path("plate").asText());
        input.put("distance",11.0);code(send("POST","/api/v1/rides",input,driver,key),409,"IDEMPOTENCY_CONFLICT");
    }
    @Test void structuredAddressesAndCoordinatesAreRejectedEvenWhenValidOrExplicitlyNull()throws Exception {
        var driver=user(true);
        for(String field:List.of("originAddress","destinationAddress","originLat","originLng","destinationLat","destinationLng"))for(boolean explicitNull:List.of(false,true)){
            var input=offer();input.put(field,explicitNull?null:field.endsWith("Address")?"Synthetic private extra address":-16.1234567);
            var response=send("POST","/api/v1/rides",input,driver,key());code(response,422,"VALIDATION_ERROR");assertFalse(response.body().contains("Synthetic private extra address"));
        }
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_routes WHERE driver_id=?",Integer.class,driver.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM rides WHERE driver_id=?",Integer.class,driver.id()));
        var schema=tree(send("GET","/api/docs/json",null,null,null));assertEquals("#/components/schemas/RideCreateInput",schema.path("paths").path("/api/v1/rides").path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText());var fields=schema.path("components").path("schemas").path("RideCreateInput").path("properties");
        assertTrue(fields.has("meeting"));assertTrue(fields.has("distance"));for(String removed:List.of("originAddress","destinationAddress","originLat","originLng","destinationLat","destinationLng"))assertFalse(fields.has(removed));
    }
    private void academicProtected(JsonNode item){var person=item.path("driver");assertEquals("",person.path("course").asText());assertEquals("protected",person.path("periodStatus").asText());assertTrue(person.path("periodNumber").isNull());assertEquals("",person.path("classLabel").asText());assertFalse(person.has("classId"));assertFalse(person.has("shift"));}
    @Test void academicProfileIsProtectedInCatalogAndAcceptedOfferWhileOwnersKeepTheirOwnValues()throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=publish(driver,offer());
        academicProtected(tree(send("GET","/api/v1/rides/"+ride,null,passenger,null)));var result=tree(send("GET",search(driver),null,passenger,null));assertEquals(1,result.path("items").size());academicProtected(result.path("items").get(0));
        var own=tree(send("GET","/api/v1/rides/"+ride,null,driver,null)).path("driver");assertEquals("Ride Course",own.path("course").asText());assertEquals(3,own.path("periodNumber").asInt());assertEquals("B",own.path("classLabel").asText());assertEquals("numbered",own.path("periodStatus").asText());
        var mine=tree(send("GET","/api/v1/rides/mine",null,driver,null));assertEquals("Ride Course",mine.path("items").get(0).path("driver").path("course").asText());
        var request=send("POST","/api/v1/ride-requests",Map.of("type","avulsa","rideId",ride.toString()),passenger,key());assertEquals(200,request.statusCode());assertEquals("accepted",tree(request).path("status").asText());var accepted=tree(send("GET","/api/v1/rides/"+ride,null,passenger,null));assertEquals("Synthetic bus stop",accepted.path("meeting").asText());academicProtected(accepted);
    }
    @Test void paidOperationalAndHistoricalRelationshipsDoNotGrantCurrentAcademicProfileAccess()throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=publish(driver,offer());var paid=contract(driver,passenger,ride);
        var operational=tree(send("GET","/api/v1/rides/"+ride,null,passenger,null));assertEquals("ABC1D23",operational.path("vehicle").path("plate").asText());academicProtected(operational);
        sql.update("UPDATE rides SET status='active' WHERE id=?",ride);sql.update("UPDATE ride_reservations SET status='awaiting_confirmation' WHERE id=?",paid[1]);sql.update("UPDATE ride_reservations SET status='completed' WHERE id=?",paid[1]);sql.update("UPDATE rides SET status='completed' WHERE id=?",ride);academicProtected(tree(send("GET","/api/v1/rides/"+ride,null,passenger,null)));
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",passenger.id(),driver.id());academicProtected(tree(send("GET","/api/v1/rides/"+ride,null,passenger,null)));
        assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paid[3]));assertEquals(400,ledger.balance(passenger.id(),AccountKind.FUTURE));assertEquals(0,ledger.reconciliation().difference());
    }
    private UUID legacyLocation(Session owner){return sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m,origin_address,destination_address,origin_lat,origin_lng,destination_lat,destination_lng) VALUES(?,?,'Synthetic region','Synthetic meeting',12000,'Synthetic private origin','Synthetic private destination',-16.1,-48.2,-16.2,-48.3) RETURNING id",UUID.class,university,owner.id());}
    private void preciseLocation(UUID route,boolean present){assertEquals(present,Boolean.TRUE.equals(sql.queryForObject("SELECT origin_address IS NOT NULL OR destination_address IS NOT NULL OR origin_lat IS NOT NULL OR origin_lng IS NOT NULL OR destination_lat IS NOT NULL OR destination_lng IS NOT NULL FROM ride_routes WHERE id=?",Boolean.class,route)));}
    private UUID locationHold(Session owner,UUID route,String category,boolean expired){return sql.queryForObject("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,created_at,review_at,expires_at) VALUES(?,?,'ride_route',?,'regular_exercise_of_rights','Synthetic fixture hold reason',?,now()-interval '3 days',now()-interval '2 days',now()+CASE WHEN ? THEN interval '-1 day' ELSE interval '1 day' END) RETURNING id",UUID.class,owner.id(),category,route,owner.id(),expired);}
    @Test void legacyLocationCleanupIsBoundedAndSpecificHoldDoesNotKeepOtherRoutes()throws Exception {
        var retention=app.getBean(PrivacyLocationRetentionTransactions.class);retention.suppressBeforeReadiness();var driver=user(true);UUID held=legacyLocation(driver),expired=legacyLocation(driver),unheld=legacyLocation(driver);
        UUID hold=locationHold(driver,held,"location",false);locationHold(driver,expired,"location",true);locationHold(driver,unheld,"profile",false);
        assertEquals(1,retention.prune(1));assertEquals(1,retention.prune(1));assertEquals(0,retention.prune(1));preciseLocation(held,true);preciseLocation(expired,false);preciseLocation(unheld,false);
        assertEquals("Synthetic meeting",sql.queryForObject("SELECT meeting FROM ride_routes WHERE id=?",String.class,unheld));assertEquals(12000,sql.queryForObject("SELECT distance_m FROM ride_routes WHERE id=?",Integer.class,unheld));
        sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE id=?",hold);assertEquals(1,retention.prune(1));preciseLocation(held,false);assertThrows(IllegalArgumentException.class,()->retention.prune(0));
        UUID global=locationHold(driver,null,"location",false),later=legacyLocation(driver);assertEquals(0,retention.prune(10));preciseLocation(later,true);sql.update("UPDATE privacy_legal_holds SET expires_at=now()-interval '1 second' WHERE id=?",global);assertEquals(1,retention.prune(10));preciseLocation(later,false);
    }
    @Test void aLocationHoldCommittedWhileCleanupWaitsWinsTheSharedDatabaseLock()throws Exception {
        var retention=app.getBean(PrivacyLocationRetentionTransactions.class);retention.suppressBeforeReadiness();var driver=user(true);UUID route=legacyLocation(driver);var inserted=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var held=pool.submit(()->tx.execute(status->{UUID result=locationHold(driver,route,"location",false);inserted.countDown();try{assertTrue(commit.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return result;}));assertTrue(inserted.await(5,TimeUnit.SECONDS));var cleanup=pool.submit(()->retention.prune(10));
            try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean waiting=false;while(System.nanoTime()<deadline){waiting=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=74019 AND objid=1 AND NOT granted)",Boolean.class));if(waiting)break;Thread.sleep(10);}assertTrue(waiting,"Cleanup must actually wait for the uncommitted hold's coordination lock");}finally{commit.countDown();}
            held.get(5,TimeUnit.SECONDS);assertEquals(0,cleanup.get(5,TimeUnit.SECONDS));preciseLocation(route,true);
        }
    }
    @Test void distanceEditDoesNotCopyUnusedLegacyPreciseLocation()throws Exception {
        var driver=user(true);UUID ride=publish(driver,offer()),old=sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,ride);
        sql.update("UPDATE ride_routes SET origin_address='Synthetic legacy private address',origin_lat=-16.1,origin_lng=-48.2 WHERE id=?",old);
        var changed=send("PATCH","/api/v1/rides/"+ride,edit(3,15),driver,key());assertEquals(200,changed.statusCode(),changed.body());UUID replacement=sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,ride);assertNotEquals(old,replacement);preciseLocation(replacement,false);preciseLocation(old,true);
        assertEquals("Synthetic bus stop",sql.queryForObject("SELECT meeting FROM ride_routes WHERE id=?",String.class,replacement));app.getBean(PrivacyLocationRetentionTransactions.class).suppressBeforeReadiness();preciseLocation(old,false);
    }
    @Test void knownOpenOccurrenceKeepsItsLocationUntilResolvedWithoutChangingContractOrLedger()throws Exception {
        var retention=app.getBean(PrivacyLocationRetentionTransactions.class);retention.suppressBeforeReadiness();var driver=user(true);var passenger=user(false);UUID ride=publish(driver,offer()),route=sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,ride);UUID[] paid=contract(driver,passenger,ride);
        sql.update("UPDATE ride_routes SET origin_address='Synthetic case location',destination_lat=-16.1 WHERE id=?",route);
        UUID occurrence=sql.queryForObject("INSERT INTO occurrences(reporter_id,other_id,reservation_id,kind,reason) VALUES(?,?,?,'safety','Synthetic location case') RETURNING id",UUID.class,passenger.id(),driver.id(),paid[1]);int entries=sql.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class);
        assertEquals(0,retention.prune(10));preciseLocation(route,true);sql.update("UPDATE occurrences SET status='resolved',outcome='no_financial_change' WHERE id=?",occurrence);assertEquals(1,retention.prune(10));preciseLocation(route,false);
        assertEquals(entries,sql.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));assertEquals("confirmed",sql.queryForObject("SELECT status FROM ride_reservations WHERE id=?",String.class,paid[1]));assertEquals("approved",sql.queryForObject("SELECT status FROM payments WHERE id=?",String.class,paid[3]));
    }
    @Test void restoredUnusedLocationIsDrainedInBatchesBeforeReadinessAndLockedDataFailsClosed()throws Exception {
        var driver=user(true);var retention=app.getBean(PrivacyLocationRetentionTransactions.class);retention.suppressBeforeReadiness();
        sql.update("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m,origin_address) SELECT ?,?,'Synthetic region','Synthetic meeting',12000,'Synthetic restored extra' FROM generate_series(1,501)",university,driver.id());
        var guard=app.getBean(PrivacyRestoreGuard.class);var readiness=app.getBean(PrivacyReadiness.class);guard.run(new org.springframework.boot.DefaultApplicationArguments());assertTrue(readiness.ready());assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_routes WHERE driver_id=? AND origin_address IS NOT NULL",Integer.class,driver.id()));
        UUID lockedRoute=legacyLocation(driver);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.execute(status->{sql.queryForList("SELECT id FROM ride_routes WHERE id=? FOR UPDATE",lockedRoute);locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));assertTrue(locked.await(5,TimeUnit.SECONDS));
            try{assertThrows(RuntimeException.class,()->guard.run(new org.springframework.boot.DefaultApplicationArguments()));assertFalse(readiness.ready());code(send("GET","/api/v1/auth/me",null,driver,null),503,"PRIVACY_RECOVERY_PENDING");preciseLocation(lockedRoute,true);}finally{release.countDown();holder.get(5,TimeUnit.SECONDS);guard.run(new org.springframework.boot.DefaultApplicationArguments());}
        }
        assertTrue(readiness.ready());preciseLocation(lockedRoute,false);
    }
    @Test void createAndEditReplaysAndReadsMatchOriginalInBothDirections() throws Exception {
        var driver=user(true);var passenger=user(false);var input=offer();input.put("toll",2.5);input.put("notes"," \ufeff normalized \u00a0");
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            String oldKey=key();var old=peer.request("POST","/api/v1/rides",input,headers(driver,oldKey));assertEquals(200,old.path("status").asInt());
            var replay=send("POST","/api/v1/rides",input,driver,oldKey);assertEquals(200,replay.statusCode());assertEquals(old.path("body"),tree(replay));UUID id=UUID.fromString(tree(replay).path("id").asText());
            input.put("time","19:00");String newKey=key();var created=send("POST","/api/v1/rides",input,driver,newKey);assertEquals(200,created.statusCode());assertEquals(tree(created),peer.request("POST","/api/v1/rides",input,headers(driver,newKey)).path("body"));
            var edit=edit(3,15.0);edit.put("toll",0.0);edit.put("version",1.0);String editKey=key();String path="/api/v1/rides/"+id.toString().toUpperCase(Locale.ROOT);
            var changed=send("PATCH",path,edit,driver,editKey);assertEquals(200,changed.statusCode());assertEquals(tree(changed),peer.request("PATCH",path,edit,headers(driver,editKey)).path("body"));
            edit.put("version",2);edit.put("distance",16.125);String secondKey=key();old=peer.request("PATCH",path,edit,headers(driver,secondKey));assertEquals(200,old.path("status").asInt());assertEquals(old.path("body"),tree(send("PATCH",path,edit,driver,secondKey)));
            for(String query:List.of(search(driver)+"&region=gama&maxPrice=0",search(driver)+"&automatic=false",search(driver)+"&limit=1&offset=1","/api/v1/rides/mine?upcoming=true","/api/v1/rides/mine?limit=1&offset=1","/api/v1/rides/"+id)) {
                var user=query.contains("mine")?driver:passenger;var expected=peer.request("GET",query,null,headers(user,null)).path("body");assertEquals(expected,tree(send("GET",query,null,user,null)));
            }
        }
    }
    @Test void editClonesSharedRouteAndPreservesEveryContractPriceAndHeldSeat() throws Exception {
        var driver=user(true);var passenger=user(false);UUID ride=publish(driver,offer()),routine=routine(driver,ride),oldRoute=sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,ride);
        var siblingInput=offer();siblingInput.put("time","19:00");UUID sibling=publish(driver,siblingInput);sql.update("UPDATE rides SET route_id=? WHERE id=?",oldRoute,sibling);
        UUID[] contract=contract(driver,passenger,ride);hold(driver,user(false),routine,ride);String rejected=key();
        code(send("PATCH","/api/v1/rides/"+ride,edit(1,20),driver,rejected),409,"RIDE_FULL");assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",Integer.class,driver.id(),rejected));
        var input=edit(2,20);input.put("version",1);var response=send("PATCH","/api/v1/rides/"+ride,input,driver,key());assertEquals(200,response.statusCode());assertEquals(2,tree(response).path("version").asInt());
        assertNotEquals(oldRoute,sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,ride));assertEquals(oldRoute,sql.queryForObject("SELECT route_id FROM rides WHERE id=?",UUID.class,sibling));assertEquals(oldRoute,sql.queryForObject("SELECT route_id FROM routines WHERE id=?",UUID.class,routine));
        assertEquals(10000,sql.queryForObject("SELECT distance_m FROM ride_routes WHERE id=?",Integer.class,oldRoute));
        assertEquals(400,sql.queryForObject("SELECT cents FROM request_lines WHERE request_id=?",Integer.class,contract[0]));assertEquals(400,sql.queryForObject("SELECT price_cents FROM ride_reservations WHERE id=?",Integer.class,contract[1]));assertEquals(400,sql.queryForObject("SELECT cents FROM parcels WHERE id=?",Integer.class,contract[2]));assertEquals(1000,sql.queryForObject("SELECT commission_bps FROM parcels WHERE id=?",Integer.class,contract[2]));
        assertEquals(0,tree(send("GET","/api/v1/rides/"+ride,null,passenger,null)).path("available").asInt());
        code(send("PATCH","/api/v1/rides/"+ride,input,driver,key()),409,"VERSION_CONFLICT");code(send("PATCH","/api/v1/rides/"+ride,edit(5,20),driver,key()),422,"INVALID_CAPACITY");
    }
    @Test void searchFiltersCampusDirectionsPricesAvailabilityApprovalsAndBilateralBlocks() throws Exception {
        var driver=user(true);var passenger=user(false);UUID first=publish(driver,offer());var later=offer();later.put("time","19:00");later.put("automatic",false);UUID second=publish(driver,later);
        var back=offer();back.put("direction","volta");back.put("time","20:00");publish(driver,back);
        assertEquals(2,tree(send("GET",search(driver)+"&region=gama",null,passenger,null)).path("items").size());
        assertEquals(0,tree(send("GET",search(driver),null,driver,null)).path("items").size());
        assertEquals(1,tree(send("GET",search(driver)+"&automatic=true",null,passenger,null)).path("items").size());
        assertEquals(2,tree(send("GET",search(driver)+"&automatic=false&maxPrice=0",null,passenger,null)).path("items").size());
        assertEquals(0,tree(send("GET",search(driver)+"&maxPrice=1",null,passenger,null)).path("items").size());
        assertEquals(second.toString(),tree(send("GET",search(driver)+"&time=19:00",null,passenger,null)).path("items").get(0).path("id").asText());
        var returns=tree(send("GET",search(driver)+"&direction=volta",null,passenger,null));assertEquals(1,returns.path("items").size());assertEquals("Ride University",returns.path("items").get(0).path("origin").asText());assertEquals("Gáma",returns.path("items").get(0).path("destination").asText());
        UUID routine=routine(driver,first);for(int i=0;i<4;i++) hold(driver,user(false),routine,first);
        assertEquals(1,tree(send("GET",search(driver)+"&onlyAvailable=true",null,passenger,null)).path("items").size());
        assertEquals(2,tree(send("GET",search(driver)+"&onlyAvailable=false",null,passenger,null)).path("items").size());
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());assertEquals(0,tree(send("GET",search(driver),null,passenger,null)).path("items").size());
        sql.update("UPDATE user_blocks SET active=false WHERE by_id=?",driver.id());sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",passenger.id(),driver.id());assertEquals(0,tree(send("GET",search(driver),null,passenger,null)).path("items").size());
        sql.update("UPDATE user_blocks SET active=false WHERE by_id=?",passenger.id());sql.update("UPDATE vehicles SET status='retired' WHERE owner_id=?",driver.id());assertEquals(0,tree(send("GET",search(driver),null,passenger,null)).path("items").size());
        assertEquals(200,send("GET","/api/v1/rides/"+first,null,passenger,null).statusCode());
        sql.update("UPDATE vehicles SET status='approved' WHERE owner_id=?",driver.id());sql.update("UPDATE driver_profiles SET status='pending' WHERE user_id=?",driver.id());assertEquals(0,tree(send("GET",search(driver),null,passenger,null)).path("items").size());
        sql.update("UPDATE driver_profiles SET status='approved' WHERE user_id=?",driver.id());sql.update("UPDATE users SET account_status='suspended' WHERE id=?",driver.id());assertEquals(0,tree(send("GET",search(driver),null,passenger,null)).path("items").size());
    }
    @Test void historicalParticipationKeepsTheContractButDoesNotExposeThePlateAfterBlockingOrCompletion() throws Exception {
        var driver=user(true);var linked=user(false);var stranger=user(false);UUID ride=publish(driver,offer());UUID[] contract=contract(driver,linked,ride);
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",linked.id(),driver.id());
        assertEquals("Placa protegida",tree(send("GET","/api/v1/rides/"+ride,null,linked,null)).path("vehicle").path("plate").asText());
        sql.update("UPDATE rides SET status='active' WHERE id=?",ride);code(send("GET","/api/v1/rides/"+ride,null,stranger,null),404,"RIDE_NOT_FOUND");
        sql.update("UPDATE ride_reservations SET status='awaiting_confirmation' WHERE id=?",contract[1]);sql.update("UPDATE ride_reservations SET status='completed' WHERE id=?",contract[1]);sql.update("UPDATE rides SET status='completed' WHERE id=?",ride);
        sql.update("UPDATE users SET account_status='suspended' WHERE id IN (?,?)",linked.id(),driver.id());
        assertEquals("Placa protegida",tree(send("GET","/api/v1/rides/"+ride,null,linked,null)).path("vehicle").path("plate").asText());assertEquals(200,send("GET","/api/v1/rides/mine",null,driver,null).statusCode());
        code(send("GET",search(driver),null,linked,null),403,"ACCOUNT_RESTRICTED");code(send("PATCH","/api/v1/rides/"+ride,edit(4,10),driver,key()),403,"ACCOUNT_RESTRICTED");
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var old=peer.request("GET","/api/v1/rides/"+ride,null,headers(linked,null)).path("body");
            assertEquals(old,tree(send("GET","/api/v1/rides/"+ride,null,linked,null)));
        }
    }
    @Test void mineOrdersActiveThenFutureAndPreservesHistoryAndPagination() throws Exception {
        var driver=user(true);UUID past=publish(driver,offer());sql.update("UPDATE rides SET ride_date=current_date-1 WHERE id=?",past);
        UUID active=publish(driver,offer());sql.update("UPDATE rides SET status='active',ride_date=current_date-2 WHERE id=?",active);
        UUID future=publish(driver,offer());var late=offer();late.put("time","20:30");UUID later=publish(driver,late);
        var upcoming=tree(send("GET","/api/v1/rides/mine?upcoming=true&limit=2",null,driver,null));assertEquals(active.toString(),upcoming.path("items").get(0).path("id").asText());assertEquals(future.toString(),upcoming.path("items").get(1).path("id").asText());assertEquals(2,upcoming.path("nextOffset").asInt());
        var last=tree(send("GET","/api/v1/rides/mine?upcoming=true&limit=2&offset=2",null,driver,null));assertEquals(later.toString(),last.path("items").get(0).path("id").asText());assertTrue(last.path("nextOffset").isNull());
        var history=tree(send("GET","/api/v1/rides/mine",null,driver,null));assertEquals(4,history.path("items").size());assertEquals(later.toString(),history.path("items").get(0).path("id").asText());history.path("items").forEach(i -> assertEquals("ABC1D23",i.path("vehicle").path("plate").asText()));
        assertEquals(0,tree(send("GET","/api/v1/rides/mine",null,user(false),null)).path("items").size());
    }
    @Test void strictInputAndQueryValidationHasTheSameErrorCodesAsOriginal() throws Exception {
        var driver=user(true);var passenger=user(false);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var invalid=new ArrayList<Map<String,Object>>();
            for(var change:List.of(Map.of("distance",0.09),Map.of("distance","10"),Map.of("capacity",1.5),Map.of("capacity",9),Map.of("date","2026-02-30"),Map.of("time","24:00"),Map.of("direction","ambos"),Map.of("automatic","true"),Map.of("region","  "),Map.of("unknown",true),Map.of("toll","2,50"),Map.of("originLat",91),Map.of("originAddress","  "),Map.of("notes","x".repeat(1201)))) { var input=offer();input.putAll(change);invalid.add(input); }
            for(String field:List.of("toll","notes","originAddress","originLat","destinationLng")) { var input=offer();input.put(field,null);invalid.add(input); }
            for(var input:invalid) { code(send("POST","/api/v1/rides",input,driver,key()),422,"VALIDATION_ERROR");assertEquals("VALIDATION_ERROR",peer.request("POST","/api/v1/rides",input,headers(driver,key())).path("body").path("code").asText()); }
            var fine=offer();fine.put("capacity",4.0);fine.put("time","18:31");assertEquals(200,send("POST","/api/v1/rides",fine,driver,key()).statusCode());fine.put("time","18:32");assertEquals(200,peer.request("POST","/api/v1/rides",fine,headers(driver,key())).path("status").asInt());
            for(String query:List.of("?unknown=true","?limit=0","?limit=101","?offset=5001","?region=%20","?automatic=1","?onlyAvailable=True","?direction=ambos","?date=2026-02-30","?time=24:00","?driverId=bad","?maxPrice=-1","?limit=1&limit=2")) {
                code(send("GET","/api/v1/rides"+query,null,passenger,null),422,"VALIDATION_ERROR");assertEquals("VALIDATION_ERROR",peer.request("GET","/api/v1/rides"+query,null,headers(passenger,null)).path("body").path("code").asText());
            }
            UUID ride=publish(driver,offer());for(Object version:List.of(0,-1,1.5,"1")) { var input=edit(4,10);input.put("version",version);code(send("PATCH","/api/v1/rides/"+ride,input,driver,key()),422,"VALIDATION_ERROR"); }
            var input=edit(4,10);input.put("version",null);code(send("PATCH","/api/v1/rides/"+ride,input,driver,key()),422,"VALIDATION_ERROR");
            input.put("version",1e30);var oldUnsafe=peer.request("PATCH","/api/v1/rides/"+ride,input,headers(driver,key()));assertEquals(422,oldUnsafe.path("status").asInt());assertEquals("VALIDATION_ERROR",oldUnsafe.path("body").path("code").asText());code(send("PATCH","/api/v1/rides/"+ride,input,driver,key()),422,"VALIDATION_ERROR");
        }
        code(send("POST","/api/v1/rides",offer(),driver,null),422,"IDEMPOTENCY_REQUIRED");code(send("GET","/api/v1/rides/bad",null,passenger,null),422,"VALIDATION_ERROR");
    }
    @Test void businessFailuresRollbackRoutesKeysAndAuditAndRespectCampusTimezone() throws Exception {
        var driver=user(true);var passenger=user(false);code(send("POST","/api/v1/rides",offer(),passenger,key()),403,"DRIVER_NOT_APPROVED");
        var capacity=offer();capacity.put("capacity",5);String failed=key();code(send("POST","/api/v1/rides",capacity,driver,failed),422,"INVALID_CAPACITY");
        var equal=offer();equal.put("region"," ride university ");code(send("POST","/api/v1/rides",equal,driver,key()),422,"INVALID_ROUTE");
        var invalidToll=offer();invalidToll.put("toll","2000.01");code(send("POST","/api/v1/rides",invalidToll,driver,key()),422,"INVALID_TOLL");invalidToll.put("toll",2.333);code(send("POST","/api/v1/rides",invalidToll,driver,key()),422,"INVALID_AMOUNT");
        var past=offer();past.put("date","2020-01-01");code(send("POST","/api/v1/rides",past,driver,key()),422,"DATE_PAST");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_routes WHERE driver_id=?",Integer.class,driver.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=?",Integer.class,driver.id()));
        sql.update("UPDATE universities SET time_zone='Pacific/Kiritimati' WHERE id=?",university);
        try { var instant=ZonedDateTime.now(ZoneId.of("Pacific/Kiritimati")).minusMinutes(5);past.put("date",instant.toLocalDate().toString());past.put("time",instant.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));code(send("POST","/api/v1/rides",past,driver,key()),422,"DATE_PAST"); }
        finally { sql.update("UPDATE universities SET time_zone='America/Sao_Paulo' WHERE id=?",university); }
        UUID ride=publish(driver,offer());code(send("PATCH","/api/v1/rides/"+ride,edit(4,10),user(true),key()),404,"RIDE_NOT_FOUND");
        sql.update("UPDATE rides SET status='active' WHERE id=?",ride);code(send("PATCH","/api/v1/rides/"+ride,edit(4,10),driver,key()),409,"INVALID_STATE");
        sql.update("UPDATE vehicles SET status='retired' WHERE owner_id=?",driver.id());code(send("POST","/api/v1/rides",offer(),driver,key()),403,"VEHICLE_NOT_APPROVED");
    }
    @Test void concurrentReplayAndVersionedEditsPersistOnlyOneOperation() throws Exception {
        var driver=user(true);String key=key();UUID ride;
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> send("POST","/api/v1/rides",offer(),driver,key));var b=executor.submit(() -> send("POST","/api/v1/rides",offer(),driver,key));var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);assertEquals(200,first.statusCode());assertEquals(200,second.statusCode());assertEquals(tree(first),tree(second));ride=UUID.fromString(tree(first).path("id").asText());
            var one=edit(3,15);one.put("version",1);var two=edit(2,20);two.put("version",1);
            a=executor.submit(() -> send("PATCH","/api/v1/rides/"+ride,one,driver,key()));b=executor.submit(() -> send("PATCH","/api/v1/rides/"+ride,two,driver,key()));first=a.get(15,TimeUnit.SECONDS);second=b.get(15,TimeUnit.SECONDS);assertEquals(Set.of(200,409),Set.of(first.statusCode(),second.statusCode()));
            code(first.statusCode()==409?first:second,409,"VERSION_CONFLICT");
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM rides WHERE driver_id=?",Integer.class,driver.id()));assertEquals(2,sql.queryForObject("SELECT version FROM rides WHERE id=?",Integer.class,ride));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='ride_edited' AND resource_id=?",Integer.class,ride));
    }
    @Test void swaggerShowsTheFiveOperationsAndRequiredMutationHeaders() throws Exception {
        var api=tree(send("GET","/api/docs/json",null,null,null));var paths=api.path("paths");
        for(var operation:List.of(paths.path("/api/v1/rides").path("post"),paths.path("/api/v1/rides/{id}").path("patch"))) {
            var required=new HashSet<String>();operation.path("parameters").forEach(p -> { if(p.path("required").asBoolean()) required.add(p.path("name").asText()); });assertTrue(required.containsAll(Set.of("idempotency-key","Origin","x-csrf-token")));assertTrue(operation.path("security").get(0).has("cookieAuth"));
        }
        var params=new HashSet<String>();paths.path("/api/v1/rides").path("get").path("parameters").forEach(p -> params.add(p.path("name").asText()));assertTrue(params.containsAll(Set.of("limit","offset","region","direction","date","time","maxPrice","driverId","automatic","onlyAvailable")));
        assertTrue(paths.path("/api/v1/rides/{id}").has("get"));assertTrue(paths.path("/api/v1/rides/mine").has("get"));code(send("GET","/api/v1/rides",null,null,null),401,"UNAUTHENTICATED");
    }
    @Test void wallClockDateAndTimeSurviveDifferentJvmTimezones() throws Exception {
        var previous=TimeZone.getDefault();
        try {
            for(String zone:List.of("UTC","Asia/Tokyo","America/Los_Angeles","America/Sao_Paulo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));var driver=user(true);UUID ride=publish(driver,offer());
                assertEquals(date+" 18:30:00",sql.queryForObject("SELECT ride_date::text||' '||departure_time::text FROM rides WHERE id=?",String.class,ride));
                var detail=tree(send("GET","/api/v1/rides/"+ride,null,driver,null));assertEquals(date,detail.path("date").asText());assertEquals("18:30",detail.path("time").asText());
                var input=edit(4,12);input.put("version",1);assertEquals(200,send("PATCH","/api/v1/rides/"+ride,input,driver,key()).statusCode());
                assertEquals(date+" 18:30:00",sql.queryForObject("SELECT ride_date::text||' '||departure_time::text FROM rides WHERE id=?",String.class,ride));
            }
        } finally { TimeZone.setDefault(previous); }
    }
    @Test void campusIsolationAndMissingAcademicFieldsKeepLegacyVisibilityAndNulls() throws Exception {
        var driver=user(true);var passenger=user(false);var foreign=user(false);UUID ride=publish(driver,offer());
        UUID other=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES(?,'Other Campus','Gama','DF') RETURNING id",UUID.class,"other-"+UUID.randomUUID());
        UUID otherCourse=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'other-course','Other Course') RETURNING id",UUID.class,other);
        UUID otherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') RETURNING id",UUID.class,other,otherCourse);
        sql.update("UPDATE student_profiles SET university_id=?,course_id=?,class_id=? WHERE user_id=?",other,otherCourse,otherClass,foreign.id());
        assertEquals(0,tree(send("GET",search(driver),null,foreign,null)).path("items").size());code(send("GET","/api/v1/rides/"+ride,null,foreign,null),404,"RIDE_NOT_FOUND");
        sql.update("DELETE FROM student_profiles WHERE user_id=?",driver.id());var detail=tree(send("GET","/api/v1/rides/"+ride,null,passenger,null));assertEquals("",detail.path("driver").path("course").asText());assertTrue(detail.path("driver").path("periodNumber").isNull());assertEquals("protected",detail.path("driver").path("periodStatus").asText());assertEquals("",detail.path("driver").path("classLabel").asText());
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { assertEquals(peer.request("GET","/api/v1/rides/"+ride,null,headers(passenger,null)).path("body"),detail); }
        sql.update("DELETE FROM student_profiles WHERE user_id=?",passenger.id());code(send("GET","/api/v1/rides",null,passenger,null),422,"PROFILE_REQUIRED");
        assertEquals(200,send("GET","/api/v1/rides/"+ride,null,driver,null).statusCode());
    }
}
