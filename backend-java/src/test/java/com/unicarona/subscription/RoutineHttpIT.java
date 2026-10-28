package com.unicarona.subscription;

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


class RoutineHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static UUID university,course,classId,otherClass;
    private static String server,passwordHash,start;
    private static final String PASSWORD="Synthetic routine HTTP 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf) { @Override public String toString() { return "Session[redacted]"; } }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        start=LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(1).withDayOfMonth(1).toString();
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('routine-university','Routine University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'routine-course','Routine Course') RETURNING id",UUID.class,university);
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
    @Test void publicationMaterializesOnlySelectedDaysAndBothDirectionsAndReplaysExactlyOnce() throws Exception {
        var driver=user(true);var input=routine();input.put("toll","2.50");input.put("notes"," \ufeff Synthetic note \u00a0");String key=key();var response=send("POST","/api/v1/routines",input,driver,key);assertEquals(200,response.statusCode());UUID id=UUID.fromString(tree(response).path("id").asText());
        assertEquals(tree(response),tree(send("POST","/api/v1/routines",input,driver,key)));assertEquals(1,sql.queryForObject("SELECT count(*) FROM routines WHERE driver_id=?",Integer.class,driver.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='routine_published' AND resource_id=?",Integer.class,id));
        int expected=sql.queryForObject("SELECT count(*)*2 FROM generate_series(date_trunc('month',now() AT TIME ZONE 'America/Sao_Paulo')::date,(date_trunc('month',now() AT TIME ZONE 'America/Sao_Paulo')+interval '1 month - 1 day')::date,interval '1 day') d WHERE d::date>=(now() AT TIME ZONE 'America/Sao_Paulo')::date AND extract(dow FROM d) IN (1,3,5)",Integer.class);
        assertEquals(expected,sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=?",Integer.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=? AND (price_cents<>? OR toll_cents<>0)",Integer.class,id,tree(response).path("priceCents").asInt()));
        var detail=tree(send("GET","/api/v1/routines/"+id,null,driver,null));assertEquals(json.readTree("[3,1,5]"),detail.path("weekdays"));assertEquals("18:00",detail.path("times").path("ida").asText());assertEquals("21:00",detail.path("times").path("volta").asText());assertEquals("Synthetic note",detail.path("notes").asText());assertTrue(detail.path("compatible").asBoolean());
        input.put("distance",11);code(send("POST","/api/v1/routines",input,driver,key),409,"IDEMPOTENCY_CONFLICT");
    }
    @Test void independentJavaInstancesPublicationReplayReadsAndPreviewMatchInBothDirections() throws Exception {
        var driver=user(true);var passenger=user(false);var input=routine();input.put("toll",2.5);input.put("capacity",4.0);input.put("weekdays",List.of(3.0,1.0,5.0));
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            String oldKey=key();var old=peer.request("POST","/api/v1/routines",input,headers(driver,oldKey));assertEquals(200,old.path("status").asInt());var replay=send("POST","/api/v1/routines",input,driver,oldKey);assertEquals(200,replay.statusCode());assertEquals(old.path("body"),tree(replay));UUID id=UUID.fromString(tree(replay).path("id").asText());
            input.put("time","19:00");input.put("returnTime","22:00");String newKey=key();var created=send("POST","/api/v1/routines",input,driver,newKey);assertEquals(200,created.statusCode());assertEquals(tree(created),peer.request("POST","/api/v1/routines",input,headers(driver,newKey)).path("body"));
            for(String path:List.of("/api/v1/routines?region=gama&limit=1","/api/v1/routines?compatibleOnly=true&direction=volta&time=21:30","/api/v1/routines?limit=1&offset=1","/api/v1/routines/mine?limit=1&offset=1","/api/v1/routines/"+id)) {
                var user=path.contains("mine")?driver:passenger;var expected=peer.request("GET",path,null,headers(user,null)).path("body");assertEquals(expected,tree(send("GET",path,null,user,null)));
            }
            var quoted=preview(passenger,id);assertEquals(quoted,peer.request("POST","/api/v1/subscriptions/preview",monthly(id),headers(passenger,null)).path("body"));
            var availability=Map.of("enabled",false);String path="/api/v1/routines/"+id.toString().toUpperCase(Locale.ROOT)+"/availability";assertEquals(peer.request("PATCH",path,availability,headers(driver,null)).path("body"),tree(send("PATCH",path,availability,driver,null)));
        }
    }
    @Test void previewAppliesKnownCampusAndClassExclusionsAndAllocatesIntegerDiscountWithoutReserving() throws Exception {
        var driver=user(true);var passenger=user(false);UUID id=publish(driver,routine());var initial=preview(passenger,id);
        var dates=new ArrayList<String>();initial.path("lines").forEach(line -> { if(!dates.contains(line.path("date").asText())) dates.add(line.path("date").asText()); });assertTrue(dates.size()>=3);
        sql.update("INSERT INTO calendar_exclusions(university_id,exclusion_date,title) VALUES(?,?::date,'Synthetic campus exclusion')",university,dates.get(0));
        sql.update("INSERT INTO calendar_exclusions(university_id,class_id,exclusion_date,title) VALUES(?,?,?::date,'Synthetic class exclusion')",university,classId,dates.get(1));
        sql.update("INSERT INTO calendar_exclusions(university_id,class_id,exclusion_date,title) VALUES(?,?,?::date,'Other class excluded')",university,otherClass,dates.get(2));
        try {
            var result=preview(passenger,id);assertEquals(2,result.path("excludedDates").size());assertEquals(json.readTree("[1,3,5]"),result.path("weekdays"));
            long peer=0,total=0;for(var line:result.path("lines")) { assertNotEquals(dates.get(0),line.path("date").asText());assertNotEquals(dates.get(1),line.path("date").asText());total+=line.path("cents").asLong();peer+=sql.queryForObject("SELECT price_cents FROM rides WHERE id=?",Integer.class,UUID.fromString(line.path("rideId").asText())); }
            assertEquals(peer,result.path("originalCents").asLong());assertEquals(total,result.path("totalCents").asLong());assertEquals((peer*9000+5000)/10000,total);assertTrue(result.path("conflicts").isEmpty());assertEquals(result,preview(passenger,id));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM subscription_schedule sc JOIN rides r ON r.id=sc.ride_id WHERE r.routine_id=?",Integer.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_requests WHERE routine_id=?",Integer.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE passenger_id=?",Integer.class,passenger.id()));
            try(var peerBackend=new JavaApplicationPeer(app, database.apiConnectionUrl())) { assertEquals(result,peerBackend.request("POST","/api/v1/subscriptions/preview",monthly(id),headers(passenger,null)).path("body")); }
        } finally { sql.update("UPDATE calendar_exclusions SET active=false WHERE university_id=?",university); }
    }
    @Test void searchRanksCompatibilityAndFiltersDirectionRegionAndBilateralBlocks() throws Exception {
        var same=user(true);var different=user(true,otherClass);var returning=user(true);var passenger=user(false);String region="Gáma "+UUID.randomUUID();
        var input=routine();input.put("region",region);input.put("direction","ida");input.remove("returnTime");UUID compatible=publish(same,input);UUID incompatible=publish(different,input);
        input.put("direction","volta");UUID volta=publish(returning,input);String query="/api/v1/routines?region="+URLEncoder.encode(region,StandardCharsets.UTF_8);
        var result=tree(send("GET",query,null,passenger,null));assertEquals(2,result.path("items").size());assertEquals(compatible.toString(),result.path("items").get(0).path("id").asText());assertTrue(result.path("items").get(0).path("compatible").asBoolean());assertFalse(result.path("items").get(1).path("compatible").asBoolean());
        assertEquals(1,tree(send("GET",query+"&compatibleOnly=true",null,passenger,null)).path("items").size());assertEquals(incompatible.toString(),tree(send("GET",query,null,same,null)).path("items").get(0).path("id").asText());
        var returns=tree(send("GET",query+"&direction=volta",null,passenger,null));assertEquals(1,returns.path("items").size());assertEquals(volta.toString(),returns.path("items").get(0).path("id").asText());assertFalse(returns.path("items").get(0).path("times").has("ida"));assertFalse(returns.path("items").get(0).path("prices").has("ida"));
        assertEquals(0,tree(send("GET",query+"&time=18:01",null,passenger,null)).path("items").size());
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",same.id(),passenger.id());code(send("GET","/api/v1/routines/"+compatible,null,passenger,null),404,"ROUTINE_NOT_FOUND");assertEquals(1,tree(send("GET",query,null,passenger,null)).path("items").size());
        sql.update("UPDATE user_blocks SET active=false WHERE by_id=?",same.id());sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",passenger.id(),same.id());assertEquals(1,tree(send("GET",query,null,passenger,null)).path("items").size());
        sql.update("UPDATE user_blocks SET active=false WHERE by_id=?",passenger.id());sql.update("UPDATE vehicles SET status='retired' WHERE owner_id=?",same.id());assertEquals(2,tree(send("GET",query,null,passenger,null)).path("items").size());
        sql.update("UPDATE driver_profiles SET status='pending' WHERE user_id=?",same.id());assertEquals(1,tree(send("GET",query,null,passenger,null)).path("items").size());
    }
    private void academicProtected(JsonNode item){var person=item.path("driver");assertEquals("",person.path("course").asText());assertEquals("protected",person.path("periodStatus").asText());assertTrue(person.path("periodNumber").isNull());assertEquals("",person.path("classLabel").asText());assertFalse(person.has("classId"));}
    @Test void monthlyCatalogSharesCompatibilityWithoutDisclosingAnotherStudentsAcademicValues()throws Exception {
        var driver=user(true);var same=user(false);var different=user(false,otherClass);UUID id=publish(driver,routine());
        for(var viewer:List.of(same,different)){var detail=tree(send("GET","/api/v1/routines/"+id,null,viewer,null));academicProtected(detail);assertEquals(viewer==same,detail.path("compatible").asBoolean());var items=tree(send("GET","/api/v1/routines",null,viewer,null)).path("items");var offer=java.util.stream.StreamSupport.stream(items.spliterator(),false).filter(row->row.path("id").asText().equals(id.toString())).findFirst().orElseThrow();academicProtected(offer);assertEquals(viewer==same,offer.path("compatible").asBoolean());}
        var own=tree(send("GET","/api/v1/routines/"+id,null,driver,null)).path("driver");assertEquals("Routine Course",own.path("course").asText());assertEquals(3,own.path("periodNumber").asInt());assertEquals("A",own.path("classLabel").asText());assertEquals("numbered",own.path("periodStatus").asText());
        assertFalse(preview(same,id).path("lines").isEmpty());code(send("POST","/api/v1/subscriptions/preview",monthly(id),different,null),422,"CLASS_MISMATCH");
        var mine=tree(send("GET","/api/v1/routines/mine",null,driver,null)).path("items");assertEquals("Routine Course",java.util.stream.StreamSupport.stream(mine.spliterator(),false).filter(row->row.path("id").asText().equals(id.toString())).findFirst().orElseThrow().path("driver").path("course").asText());
    }
    @Test void availabilityPreservesPaidTripsAndNotifiesOnlyEligibleWaitingStudentsOnce() throws Exception {
        var driver=user(true);UUID id=publish(driver,routine());var eligible=user(false);var disabled=user(false);var different=user(false,otherClass);var restricted=user(false);var blocked=user(false);
        for(var passenger:List.of(eligible,disabled,different,restricted,blocked)) sql.update("INSERT INTO waitlist(user_id,routine_id) VALUES(?,?)",passenger.id(),id);
        sql.update("UPDATE student_profiles SET notices=false WHERE user_id=?",disabled.id());sql.update("UPDATE users SET account_status='suspended' WHERE id=?",restricted.id());sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),blocked.id());
        int trips=sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=?",Integer.class,id);
        var pause=send("PATCH","/api/v1/routines/"+id+"/availability",Map.of("enabled",false),driver,null);assertEquals(200,pause.statusCode());assertEquals("paused",tree(pause).path("status").asText());
        assertEquals(trips,sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=? AND status='planned'",Integer.class,id));code(send("GET","/api/v1/routines/"+id,null,eligible,null),404,"ROUTINE_NOT_FOUND");
        assertEquals(200,send("GET","/api/v1/routines/mine",null,driver,null).statusCode());
        for(int i=0;i<2;i++) assertEquals(200,send("PATCH","/api/v1/routines/"+id+"/availability",Map.of("enabled",true),driver,null).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE ref_id=?",Integer.class,id));assertEquals(eligible.id(),sql.queryForObject("SELECT user_id FROM notifications WHERE ref_id=?",UUID.class,id));assertEquals(3,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='routine_availability' AND resource_id=?",Integer.class,id));
        var differentOwner=user(true);code(send("PATCH","/api/v1/routines/"+id+"/availability",Map.of("enabled",false),differentOwner,null),404,"ROUTINE_NOT_FOUND");
    }
    @Test void historicalSubscriptionDoesNotBypassBlockOrExposeCurrentRoutineDetails() throws Exception {
        var driver=user(true);var passenger=user(false);UUID id=publish(driver,routine());subscription(driver,passenger,id,LocalDate.parse(start).minusDays(1).toString());
        sql.update("UPDATE routines SET status='paused' WHERE id=?",id);sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",passenger.id(),driver.id());sql.update("UPDATE users SET account_status='suspended' WHERE id IN (?,?)",passenger.id(),driver.id());
        code(send("GET","/api/v1/routines/"+id,null,passenger,null),404,"ROUTINE_NOT_FOUND");assertEquals(200,send("GET","/api/v1/routines/"+id,null,driver,null).statusCode());
        code(send("GET","/api/v1/routines/mine",null,driver,null),403,"ACCOUNT_RESTRICTED");code(send("POST","/api/v1/subscriptions/preview",monthly(id),passenger,null),403,"ACCOUNT_RESTRICTED");
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { var protectedRead=peer.request("GET","/api/v1/routines/"+id,null,headers(passenger,null));assertEquals(404,protectedRead.path("status").asInt());assertEquals("ROUTINE_NOT_FOUND",protectedRead.path("body").path("code").asText());assertFalse(protectedRead.path("body").has("meeting")); }
    }
    @Test void monthlyEligibilityValidatesFullClassDirectionDaysDateRenewalAndBlocks() throws Exception {
        var driver=user(true);var passenger=user(false);var mismatch=user(false,otherClass);var input=routine();input.put("direction","ida");input.remove("returnTime");UUID id=publish(driver,input);
        var body=monthly(id);body.put("directions","ida");code(send("POST","/api/v1/subscriptions/preview",body,mismatch,null),422,"CLASS_MISMATCH");
        code(send("POST","/api/v1/subscriptions/preview",body,driver,null),409,"ROUTINE_UNAVAILABLE");
        body.put("weekdays",List.of(0));code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),422,"INVALID_WEEKDAYS");body.put("weekdays",List.of(1,3,5));body.put("directions","volta");code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),422,"INVALID_DIRECTION");
        body.put("directions","ida");body.put("startDate","2020-01-01");code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),422,"DATE_PAST");body.put("startDate",start);
        body.put("previousContractId",UUID.randomUUID().toString());code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),409,"RENEWAL_UNAVAILABLE");body.remove("previousContractId");
        sql.update("INSERT INTO user_blocks(by_id,other_id) VALUES(?,?)",driver.id(),passenger.id());code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),409,"COMBINATION_UNAVAILABLE");sql.update("UPDATE user_blocks SET active=false WHERE by_id=?",driver.id());
        sql.update("UPDATE routines SET status='paused' WHERE id=?",id);code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),409,"ROUTINE_UNAVAILABLE");sql.update("UPDATE routines SET status='active' WHERE id=?",id);
        sql.update("UPDATE student_profiles SET class_id=NULL,period_status='undefined',period_number=NULL WHERE user_id=?",driver.id());code(send("POST","/api/v1/routines",routine(),driver,key()),422,"CLASS_REQUIRED");
    }
    @Test void ownRenewalHoldsAreExcludedFromConflictsButAnotherContractStillOccupiesTheSeat() throws Exception {
        var driver=user(true);var passenger=user(false);var input=routine();input.put("capacity",1);UUID id=publish(driver,input);var initial=preview(passenger,id);UUID ride=UUID.fromString(initial.path("lines").get(0).path("rideId").asText());
        UUID previous=subscription(driver,passenger,id,LocalDate.parse(start).minusDays(1).toString());sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,400,true)",previous,ride);
        var plain=preview(passenger,id);assertEquals(ride.toString(),plain.path("conflicts").get(0).asText());
        var body=monthly(id);body.put("previousContractId",previous.toString());var response=send("POST","/api/v1/subscriptions/preview",body,passenger,null);assertEquals(200,response.statusCode());assertTrue(tree(response).path("conflicts").isEmpty());
        sql.update("UPDATE subscriptions SET renew=false WHERE id=?",previous);code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),409,"RENEWAL_UNAVAILABLE");
        sql.update("UPDATE subscriptions SET renew=true WHERE id=?",previous);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { assertEquals(tree(response),peer.request("POST","/api/v1/subscriptions/preview",body,headers(passenger,null)).path("body")); }
    }
    @Test void previewUsesLatestDiscountAndFrozenLegPricesAndReportsUnusableRideStates() throws Exception {
        var driver=user(true);var passenger=user(false);UUID id=publish(driver,routine());var first=preview(passenger,id);UUID ride=UUID.fromString(first.path("lines").get(0).path("rideId").asText());
        sql.update("UPDATE rides SET status='no_class' WHERE id=?",ride);
        sql.update("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,900,8,30,3,2000,2000)",university);
        var second=preview(passenger,id);assertEquals(first.path("originalCents"),second.path("originalCents"));assertEquals(0.2,second.path("discount").asDouble());assertEquals(ride.toString(),second.path("conflicts").get(0).asText());
        long total=0;for(var line:second.path("lines")) { total+=line.path("cents").asLong();assertEquals(0.1,line.path("commission").asDouble()); }
        assertEquals((second.path("originalCents").asLong()*8000+5000)/10000,total);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) { assertEquals(second,peer.request("POST","/api/v1/subscriptions/preview",monthly(id),headers(passenger,null)).path("body")); }
    }
    @Test void strictSchemasRejectUnknownNullRepeatedDaysAndBadQueriesLikeOriginal() throws Exception {
        var driver=user(true);var passenger=user(false);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var invalid=new ArrayList<Map<String,Object>>();
            for(var change:List.of(Map.of("weekdays",List.of()),Map.of("weekdays",List.of(1,1)),Map.of("weekdays",List.of(7)),Map.of("weekdays",List.of(1.5)),Map.of("weekdays",List.of("1")),Map.of("capacity",1.5),Map.of("distance","10"),Map.of("direction","bad"),Map.of("returnTime","24:00"),Map.of("unknown",true))) { var input=routine();input.putAll(change);invalid.add(input); }
            for(String field:List.of("returnTime","weekdays","toll","notes")) { var input=routine();input.put(field,null);invalid.add(input); }var missing=routine();missing.remove("returnTime");invalid.add(missing);
            for(var input:invalid) { code(send("POST","/api/v1/routines",input,driver,key()),422,"VALIDATION_ERROR");assertEquals("VALIDATION_ERROR",peer.request("POST","/api/v1/routines",input,headers(driver,key())).path("body").path("code").asText()); }
            UUID id=publish(driver,routine());for(String field:List.of("previousContractId","weekdays")) { var body=monthly(id);body.put(field,null);code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),422,"VALIDATION_ERROR"); }
            for(var change:List.of(Map.of("startDate","2026-02-30"),Map.of("weekdays",List.of(1,1)),Map.of("directions","bad"),Map.of("unknown",true))) { var body=monthly(id);body.putAll(change);code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),422,"VALIDATION_ERROR"); }
            for(String query:List.of("?limit=0","?offset=5001","?direction=ambos","?time=24:00","?region=%20","?compatibleOnly=1","?unknown=true","?limit=1&limit=2")) { code(send("GET","/api/v1/routines"+query,null,passenger,null),422,"VALIDATION_ERROR");assertEquals("VALIDATION_ERROR",peer.request("GET","/api/v1/routines"+query,null,headers(passenger,null)).path("body").path("code").asText()); }
        }
        code(send("POST","/api/v1/routines",routine(),driver,null),422,"IDEMPOTENCY_REQUIRED");code(send("GET","/api/v1/routines/bad",null,passenger,null),422,"VALIDATION_ERROR");
        code(send("PATCH","/api/v1/routines/"+UUID.randomUUID()+"/availability",Map.of("enabled","true"),driver,null),422,"VALIDATION_ERROR");
    }
    @Test void publicationConflictRollsBackRoutineRouteAuditAndKeyAndEmptyQuoteCreatesNoContract() throws Exception {
        var driver=user(true);UUID first=publish(driver,routine());String conflict=key();code(send("POST","/api/v1/routines",routine(),driver,conflict),409,"CONFLICT");
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM routines WHERE driver_id=?",Integer.class,driver.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM ride_routes WHERE driver_id=?",Integer.class,driver.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",Integer.class,driver.id(),conflict));
        var passenger=user(false);code(send("POST","/api/v1/routines",routine(),passenger,key()),403,"DRIVER_NOT_APPROVED");
        var secondDriver=user(true);var input=routine();input.put("capacity",5);code(send("POST","/api/v1/routines",input,secondDriver,key()),422,"INVALID_CAPACITY");
        var end=LocalDate.parse(start).withDayOfMonth(LocalDate.parse(start).lengthOfMonth());int day=(end.getDayOfWeek().getValue()+1)%7;input=routine();input.put("weekdays",List.of(day));UUID empty=publish(secondDriver,input);
        var body=monthly(empty);body.put("startDate",end.toString());body.put("weekdays",List.of(day));code(send("POST","/api/v1/subscriptions/preview",body,passenger,null),422,"EMPTY_SCHEDULE");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM subscriptions WHERE routine_id=?",Integer.class,empty));assertEquals(0,sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=? AND ride_date>=?::date",Integer.class,empty,start));assertTrue(first!=null);
    }
    @Test void concurrentPublicationAndQuotesDoNotDuplicateRoutineOrMaterializedLegs() throws Exception {
        var driver=user(true);var passenger=user(false);String key=key();UUID id;
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> send("POST","/api/v1/routines",routine(),driver,key));var b=executor.submit(() -> send("POST","/api/v1/routines",routine(),driver,key));var one=a.get(15,TimeUnit.SECONDS);var two=b.get(15,TimeUnit.SECONDS);assertEquals(200,one.statusCode());assertEquals(200,two.statusCode());assertEquals(tree(one),tree(two));id=UUID.fromString(tree(one).path("id").asText());
            a=executor.submit(() -> send("POST","/api/v1/subscriptions/preview",monthly(id),passenger,null));b=executor.submit(() -> send("POST","/api/v1/subscriptions/preview",monthly(id),passenger,null));one=a.get(15,TimeUnit.SECONDS);two=b.get(15,TimeUnit.SECONDS);assertEquals(200,one.statusCode());assertEquals(200,two.statusCode());assertEquals(tree(one),tree(two));
            assertEquals(tree(one).path("lines").size(),sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=? AND ride_date>=?::date",Integer.class,id,start));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='routine_published' AND resource_id=?",Integer.class,id));
    }
    @Test void schemaAndWallClockMappingsRemainCorrectAcrossJvmTimezones() throws Exception {
        var previous=TimeZone.getDefault();
        try {
            for(String zone:List.of("UTC","Asia/Tokyo","America/Sao_Paulo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));var driver=user(true);var passenger=user(false);UUID id=publish(driver,routine());var detail=tree(send("GET","/api/v1/routines/"+id,null,driver,null));assertEquals("18:00",detail.path("times").path("ida").asText());assertEquals("21:00",detail.path("times").path("volta").asText());
                assertEquals("18:00:00 21:00:00",sql.queryForObject("SELECT ida_time::text||' '||volta_time::text FROM routines WHERE id=?",String.class,id));var quote=preview(passenger,id);quote.path("lines").forEach(line -> assertEquals(line.path("direction").asText().equals("ida")?"18:00":"21:00",line.path("time").asText()));
            }
        } finally { TimeZone.setDefault(previous); }
        var api=tree(send("GET","/api/docs/json",null,null,null));var paths=api.path("paths");assertTrue(paths.path("/api/v1/subscriptions/preview").has("post"));assertTrue(paths.path("/api/v1/routines/{id}/availability").has("patch"));assertTrue(paths.path("/api/v1/routines/mine").has("get"));
        var required=new HashSet<String>();paths.path("/api/v1/routines").path("post").path("parameters").forEach(p -> { if(p.path("required").asBoolean()) required.add(p.path("name").asText()); });assertTrue(required.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));
    }
    @Test void pauseCommittedWhileQuoteWaitsForDriverLockIsRecheckedBeforeMaterializing() throws Exception {
        var driver=user(true);var passenger=user(false);UUID id=publish(driver,routine());
        try(var connection=app.getBean(javax.sql.DataSource.class).getConnection();var executor=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try {
                try(var lock=connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) { lock.setObject(1,driver.id());try(var row=lock.executeQuery()) { assertTrue(row.next()); } }
                var response=executor.submit(() -> send("POST","/api/v1/subscriptions/preview",monthly(id),passenger,null));
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%ORDER BY u.id FOR UPDATE OF u%')",Boolean.class));
                    if(waiting) break;Thread.sleep(20);
                }
                assertTrue(waiting);
                try(var pause=connection.prepareStatement("UPDATE routines SET status='paused' WHERE id=?")) { pause.setObject(1,id);pause.executeUpdate(); }
                connection.commit();code(response.get(10,TimeUnit.SECONDS),409,"ROUTINE_UNAVAILABLE");
                assertEquals(0,sql.queryForObject("SELECT count(*) FROM rides WHERE routine_id=? AND ride_date>=?::date",Integer.class,id,start));
            } finally { connection.rollback(); }
        }
    }
    @Test void calendarRemainsSharedLockedUntilTheWholeMonthlyTransactionCommits() throws Exception {
        var driver=user(true);var passenger=user(false);UUID id=publish(driver,routine());
        var complete=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(() -> {
                var transaction=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                return transaction.execute(status -> {
                    var body=new com.unicarona.subscription.dto.SubscriptionDto.MonthlyInput(id.toString(),start,"ambos",List.of(1,3,5),null);
                    var quote=app.getBean(com.unicarona.subscription.service.MonthlyPreview.class).calculate(passenger.id(),body,true);complete.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic transaction gate timed out."); } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic gate interrupted."); }return quote;
                });
            });
            try {
                assertTrue(complete.await(10,TimeUnit.SECONDS));
                try(var connection=app.getBean(javax.sql.DataSource.class).getConnection()) {
                    connection.setAutoCommit(false);try {
                        try(var timeout=connection.createStatement()) { timeout.execute("SET LOCAL lock_timeout='100ms'"); }
                        try(var calendarLock=connection.prepareStatement("SELECT id FROM universities WHERE id=? FOR UPDATE")) {
                            calendarLock.setObject(1,university);var error=assertThrows(java.sql.SQLException.class,calendarLock::executeQuery);assertEquals("55P03",error.getSQLState());
                        }
                    } finally { connection.rollback(); }
                }
            } finally { release.countDown(); }
            assertFalse(result.get(10,TimeUnit.SECONDS).lines().isEmpty());
        }
        try(var connection=app.getBean(javax.sql.DataSource.class).getConnection();var lock=connection.prepareStatement("SELECT id FROM universities WHERE id=? FOR UPDATE")) { lock.setObject(1,university);try(var row=lock.executeQuery()) { assertTrue(row.next()); } }
    }
}
