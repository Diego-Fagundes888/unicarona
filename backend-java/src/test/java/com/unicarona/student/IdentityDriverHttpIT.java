package com.unicarona.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.service.AuthService;
import com.unicarona.driver.dto.DriverDto;
import com.unicarona.driver.service.DriverTransactions;
import com.unicarona.security.Actor;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.student.dto.IdentityDto;
import com.unicarona.student.service.IdentityTransactions;
import com.unicarona.user.repository.ProfileRepository;
import com.unicarona.support.AuthTestSettings;
import com.unicarona.support.JavaApplicationPeer;
import com.unicarona.support.RealPostgres;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class IdentityDriverHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static UUID university,course,classId;
    private static String server,passwordHash;
    private static final String PASSWORD="Synthetic identity driver 2026";
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf,String access) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('identity-university','Identity University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'identity-course','Identity Course') RETURNING id",UUID.class,university);
        classId=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'A') RETURNING id",UUID.class,university,course);
    }
    @BeforeEach void limits() { sql.update("DELETE FROM rate_limits"); }
    @AfterAll static void close() { if(app!=null) app.close(); if(database!=null) database.close(); }
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN);
        if(session!=null) request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());
        if(key!=null) request.header("idempotency-key",key);
        if(body!=null) request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else request.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void code(HttpResponse<String> response,int status,String code) throws Exception {
        assertEquals(status,response.statusCode());assertEquals(code,tree(response).get("code").asText());
    }
    private Session user(boolean admin) throws Exception {
        String email="identity-"+UUID.randomUUID()+"@example.test";
        UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,role,email_verified_at) VALUES(?,?,'Synthetic Identity','2000-05-01',?,now()) RETURNING id",UUID.class,email,passwordHash,admin?"admin":"user");
        com.unicarona.support.LegalTestFixtures.acceptedBusinessUser(app,id);
        if(!admin) sql.update("INSERT INTO student_profiles(user_id,university_id,course_id,class_id,period_status,period_number,shift,region_label) VALUES(?,?,?,?,'numbered',3,'night','Gama')",id,university,course,classId);
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();
        for(String header:response.headers().allValues("set-cookie")) { var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]); }
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"),cookies.get("uc_access"));
    }
    private Actor actor(Session user) { return app.getBean(AuthService.class).authenticate(user.access()); }
    private String key() { return "java-domain-"+UUID.randomUUID(); }
    private UUID document(Session owner,String purpose) {
        UUID id=UUID.randomUUID();byte[] content="%PDF-1.7\nSynthetic reviewed driver document\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try{String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));app.getBean(com.unicarona.file.storage.PrivateFileStorage.class).put(id.toString(),content);sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,?,?,'application/pdf',?,?)",id,owner.id(),purpose,id.toString(),content.length,sha);return id;}
        catch(Exception unavailable){throw new IllegalStateException("Synthetic document fixture failed",unavailable);}
    }
    private Map<String,Object> application(Session owner,int capacity) {
        var input=new LinkedHashMap<String,Object>(); input.put("model"," Synthetic Car ");input.put("color"," Black ");input.put("plate","abc-1d23");input.put("capacity",capacity);
        input.put("identityFileId",document(owner,"identity").toString());input.put("licenseFileId",document(owner,"license").toString());return input;
    }
    private UUID apply(Session owner,Map<String,Object> input,String key) throws Exception {
        var response=send("POST","/api/v1/drivers/applications",input,owner,key);assertEquals(200,response.statusCode());return UUID.fromString(tree(response).get("id").asText());
    }
    private HttpResponse<String> review(Session admin,UUID application,boolean approve,String key) throws Exception {
        return send("POST","/api/v1/admin/drivers/applications/"+application+"/review",Map.of("approve",approve,"reason"," Synthetic review reason "),admin,key);
    }
    @Test void preferencesRemainAvailableForRestrictedUsersWithoutSuppressingEssentialNotices() throws Exception {
        var user=user(false);sql.update("UPDATE users SET account_status='suspended' WHERE id=?",user.id());
        var response=send("PATCH","/api/v1/users/me/settings",Map.of("notices",false,"withdrawal","monthly"),user,null);
        assertEquals(200,response.statusCode());assertEquals(json.readTree("{\"notices\":false,\"withdrawal\":\"monthly\"}"),tree(response));
        assertEquals("monthly",sql.queryForObject("SELECT withdrawal_cadence FROM student_profiles WHERE user_id=?",String.class,user.id()));
        code(send("POST","/api/v1/users/me/academic-verification",Map.of(),user,null),403,"ACCOUNT_RESTRICTED");
        assertEquals(200,send("GET","/api/v1/notifications",null,user,null).statusCode());
        var admin=user(true);code(send("PATCH","/api/v1/users/me/settings",Map.of("notices",true,"withdrawal","daily"),admin,null),404,"PROFILE_UNAVAILABLE");
        code(send("PATCH","/api/v1/users/me/settings",Map.of("notices","true","withdrawal","daily"),user,null),422,"VALIDATION_ERROR");
    }
    @Test void academicRequestQueueDecisionNotificationAndAuditAreAtomic() throws Exception {
        var user=user(false);var admin=user(true);
        send("PATCH","/api/v1/users/me/settings",Map.of("notices",false,"withdrawal","daily"),user,null);
        var request=send("POST","/api/v1/users/me/academic-verification",Map.of(),user,null);assertEquals(200,request.statusCode());
        assertEquals(json.readTree("{\"status\":\"pending\",\"source\":\"administrative\"}"),tree(request));
        code(send("GET","/api/v1/admin/academic-verifications",null,user,null),403,"FORBIDDEN");
        var queue=tree(send("GET","/api/v1/admin/academic-verifications",null,admin,null));
        assertTrue(java.util.stream.StreamSupport.stream(queue.get("items").spliterator(),false).anyMatch(item -> item.get("id").asText().equals(user.id().toString())));
        var decision=send("POST","/api/v1/admin/academic-verifications/"+user.id()+"/review",Map.of("approve",true,"reason"," Confirmed academic link "),admin,null);
        assertEquals(200,decision.statusCode());assertEquals("verified",tree(decision).get("status").asText());
        assertTrue(sql.queryForObject("SELECT verified_at IS NOT NULL FROM student_profiles WHERE user_id=?",Boolean.class,user.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND title='Vínculo acadêmico analisado'",Integer.class,user.id()));
        assertEquals("Confirmed academic link",sql.queryForObject("SELECT metadata->>'reason' FROM audit_logs WHERE resource_id=? AND action='academic_verification_reviewed'",String.class,user.id()));
        code(send("POST","/api/v1/admin/academic-verifications/"+user.id()+"/review",Map.of("approve",false,"reason","Other decision"),admin,null),409,"VERIFICATION_STATE");
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,user.id()));
    }
    @Test void concurrentAdministrativeDecisionsOnlyWriteOneNoticeAndAudit() throws Exception {
        var user=user(false);var admin=user(true);send("POST","/api/v1/users/me/academic-verification",Map.of(),user,null);
        var tx=app.getBean(IdentityTransactions.class);var administrator=actor(admin);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<String> approve=() -> { gate.await();try { return tx.review(administrator,user.id(),new IdentityDto.Review(true,"Confirmed concurrent")).status(); } catch(ApiException e) { return e.code(); } };
            var a=executor.submit(approve);var b=executor.submit(approve);gate.countDown();
            assertEquals(Set.of("verified","VERIFICATION_STATE"),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,user.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='academic_verification_reviewed'",Integer.class,user.id()));
    }
    @Test void profileChangedWhileAnExternalAdapterRunsCannotBeVerifiedWithAnOldSnapshot() throws Exception {
        var user=user(false);var expected=app.getBean(ProfileRepository.class).find(user.id());
        UUID anotherClass=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,3,'B') ON CONFLICT(course_id,period_number,label) DO UPDATE SET label=excluded.label RETURNING id",UUID.class,university,course);
        sql.update("UPDATE student_profiles SET class_id=? WHERE user_id=?",anotherClass,user.id());
        var error=assertThrows(ApiException.class,() -> app.getBean(IdentityTransactions.class).applyVerification(actor(user),expected,new IdentityDto.Verification("verified","synthetic-institutional")));
        assertEquals("PROFILE_CHANGED",error.code());assertEquals("self_reported",sql.queryForObject("SELECT verification_status FROM student_profiles WHERE user_id=?",String.class,user.id()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=?",Integer.class,user.id()));
    }
    @Test void notificationOwnershipStableReadTimestampPaginationAndRestrictedAccessArePreserved() throws Exception {
        var owner=user(false);var other=user(false);
        var ids=new ArrayList<UUID>();for(int i=0;i<3;i++) ids.add(sql.queryForObject("INSERT INTO notifications(user_id,title,body,route) VALUES(?,'Synthetic notice','Essential content','perfil') RETURNING id",UUID.class,owner.id()));
        var page=tree(send("GET","/api/v1/notifications?limit=1&offset=1",null,owner,null));assertEquals(1,page.get("items").size());assertEquals(2,page.get("nextOffset").asInt());assertEquals(3,page.get("unread").asInt());
        var notice=ids.getFirst();code(send("POST","/api/v1/notifications/"+notice+"/read",Map.of(),other,null),404,"NOTIFICATION_NOT_FOUND");
        var first=tree(send("POST","/api/v1/notifications/"+notice+"/read",Map.of(),owner,null));
        assertEquals(first,tree(send("POST","/api/v1/notifications/"+notice+"/read",Map.of(),owner,null)));
        assertTrue(first.get("readAt").asText().matches(".*\\.\\d{3}Z"));
        assertEquals(2,tree(send("GET","/api/v1/notifications",null,owner,null)).get("unread").asInt());
        assertEquals(0,tree(send("GET","/api/v1/notifications",null,other,null)).get("items").size());
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",owner.id());assertEquals(200,send("POST","/api/v1/notifications/"+ids.get(1)+"/read",Map.of(),owner,null).statusCode());
        code(send("GET","/api/v1/notifications?limit=0",null,owner,null),422,"VALIDATION_ERROR");
        code(send("GET","/api/v1/notifications?other=1",null,owner,null),422,"VALIDATION_ERROR");
        code(send("GET","/api/v1/notifications?limit=1&limit=2",null,owner,null),422,"VALIDATION_ERROR");
        code(send("POST","/api/v1/notifications/not-a-uuid/read",Map.of(),owner,null),422,"VALIDATION_ERROR");
    }
    @Test void driverApplicationRequiresOwnDocumentsAndAnIdempotencyKey() throws Exception {
        var owner=user(false);var other=user(false);var input=application(owner,4);
        code(send("POST","/api/v1/drivers/applications",input,owner,null),422,"IDEMPOTENCY_REQUIRED");
        input.put("identityFileId",document(other,"identity").toString());String key=key();
        code(send("POST","/api/v1/drivers/applications",input,owner,key),422,"DOCUMENTS_REQUIRED");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=?",Integer.class,owner.id()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM vehicles WHERE owner_id=?",Integer.class,owner.id()));
        input.put("identityFileId",document(owner,"identity").toString());var first=send("POST","/api/v1/drivers/applications",input,owner,key);assertEquals(200,first.statusCode());
        assertEquals(tree(first),tree(send("POST","/api/v1/drivers/applications",input,owner,key)));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicles WHERE owner_id=?",Integer.class,owner.id()));
        assertEquals("ABC1D23",sql.queryForObject("SELECT plate FROM vehicles WHERE owner_id=?",String.class,owner.id()));
        input.put("capacity",3);code(send("POST","/api/v1/drivers/applications",input,owner,key),409,"IDEMPOTENCY_CONFLICT");
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,owner.id()));
    }
    @Test void replacementRejectsReviewOfAnOldApplicationAndApprovalGrantsDriverAuthority() throws Exception {
        var owner=user(false);var admin=user(true);UUID peer=apply(owner,application(owner,4),key());UUID next=apply(owner,application(owner,3),key());
        assertEquals("replaced",sql.queryForObject("SELECT status FROM driver_applications WHERE id=?",String.class,peer));
        code(review(admin,peer,true,key()),409,"APPLICATION_STATE");
        code(review(owner,next,true,key()),403,"FORBIDDEN");
        String reviewKey=key();var approved=review(admin,next,true,reviewKey);assertEquals(200,approved.statusCode());
        assertEquals(tree(approved),tree(review(admin,next,true,reviewKey)));
        assertTrue(actor(owner).authorities().contains("ROLE_DRIVER"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicles WHERE owner_id=? AND status='approved'",Integer.class,owner.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=? AND action='driver_review'",Integer.class,next));
        assertEquals(0,tree(send("GET","/api/v1/drivers/applications",null,user(false),null)).get("items").size());
        assertEquals(1,tree(send("GET","/api/v1/drivers/applications?status=approved",null,owner,null)).get("items").size());
    }
    @Test void concurrentRepeatedApplicationsProduceOneVehicleAndOneNotice() throws Exception {
        var owner=user(false);var body=application(owner,4);String key=key();
        var input=new DriverDto.Apply((String)body.get("model"),(String)body.get("color"),(String)body.get("plate"),4,(String)body.get("identityFileId"),(String)body.get("licenseFileId"));
        var tx=app.getBean(DriverTransactions.class);var applicant=actor(owner);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<DriverDto.Status> action=() -> {gate.await();return tx.apply(applicant,input,key);};
            var a=executor.submit(action);var b=executor.submit(action);gate.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM driver_applications WHERE user_id=?",Integer.class,owner.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,owner.id()));
    }
    @Test void independentJavaInstancesShareIdempotentDriverResponsesAndTimestampedLists() throws Exception {
        var owner=user(false);var admin=user(true);var input=application(owner,4);String key=key();
        var headers=Map.of("origin",AuthTestSettings.ORIGIN,"cookie",owner.cookie(),"x-csrf-token",owner.csrf(),"idempotency-key",key);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var old=peer.request("POST","/api/v1/drivers/applications",input,headers);var current=send("POST","/api/v1/drivers/applications",input,owner,key);
            assertEquals(old.get("status").asInt(),current.statusCode());assertEquals(old.get("body"),tree(current));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM vehicles WHERE owner_id=?",Integer.class,owner.id()));
            for(String path:List.of("/api/v1/drivers/applications","/api/v1/notifications","/api/v1/users/me")) {
                var oldList=peer.request("GET",path,null,Map.of("cookie",owner.cookie()));var javaList=send("GET",path,null,owner,null);
                assertEquals(oldList.get("status").asInt(),javaList.statusCode());JsonNode currentList=tree(javaList);
                if(path.equals("/api/v1/drivers/applications")){
                    for(JsonNode item:currentList.path("items")){assertEquals("available",item.path("documentsStatus").asText());assertEquals("driver-documents-v1",item.path("documentsPolicyVersion").asText());assertEquals(com.unicarona.shared.ApiDates.timestamp(sql.queryForObject("SELECT documents_expires_at FROM driver_applications WHERE id=?",java.sql.Timestamp.class,UUID.fromString(item.path("id").asText())).toInstant()),item.path("documentsExpiresAt").asText());}
                }
                assertEquals(oldList.get("body"),currentList);
            }
            var own=tree(current);UUID application=UUID.fromString(own.get("id").asText());String reviewKey=key();var reviewBody=Map.of("approve",false,"reason","Synthetic review reason");
            String path="/api/v1/admin/drivers/applications/"+application+"/review";
            var newReview=send("POST",path,reviewBody,admin,reviewKey);
            var oldReview=peer.request("POST",path,reviewBody,Map.of("origin",AuthTestSettings.ORIGIN,"cookie",admin.cookie(),"x-csrf-token",admin.csrf(),"idempotency-key",reviewKey));
            assertEquals(200,newReview.statusCode());assertEquals(oldReview.get("body"),tree(newReview));
            assertFalse(actor(owner).authorities().contains("ROLE_DRIVER"));
            var settings=Map.of("notices",false,"withdrawal","monthly");
            var oldSettings=peer.request("PATCH","/api/v1/users/me/settings",settings,headers);
            assertEquals(oldSettings.get("body"),tree(send("PATCH","/api/v1/users/me/settings",settings,owner,null)));
            var oldRequest=peer.request("POST","/api/v1/users/me/academic-verification",Map.of(),headers);
            assertEquals(oldRequest.get("body"),tree(send("POST","/api/v1/users/me/academic-verification",Map.of(),owner,null)));
            var oldQueue=peer.request("GET","/api/v1/admin/academic-verifications",null,Map.of("cookie",admin.cookie()));
            assertEquals(oldQueue.get("body"),tree(send("GET","/api/v1/admin/academic-verifications",null,admin,null)));
            send("POST","/api/v1/admin/academic-verifications/"+owner.id()+"/review",Map.of("approve",false,"reason","Synthetic academic decision"),admin,null);
        }
    }
    @Test void approvingASmallerVehicleRespectsHeldSeatsAndPreservesActiveRideEvidence() throws Exception {
        var owner=user(false);var admin=user(true);UUID first=apply(owner,application(owner,4),key());assertEquals(200,review(admin,first,true,key()).statusCode());
        UUID oldVehicle=sql.queryForObject("SELECT vehicle_id FROM driver_applications WHERE id=?",UUID.class,first);
        UUID pricing=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,1000) RETURNING id",UUID.class,university);
        UUID route=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Gama','Campus',10000) RETURNING id",UUID.class,university,owner.id());
        UUID routine=sql.queryForObject("INSERT INTO routines(route_id,driver_id,university_id,vehicle_id,class_id,weekdays,ida_time,ida_cents,capacity,pricing_version_id) VALUES(?,?,?,?,?,ARRAY[1,2]::smallint[],'18:00',600,4,?) RETURNING id",UUID.class,route,owner.id(),university,oldVehicle,classId,pricing);
        UUID ride=sql.queryForObject("INSERT INTO rides(university_id,route_id,routine_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,?,'ida',current_date+1,'18:00',4,600,1000,?) RETURNING id",UUID.class,university,route,routine,owner.id(),oldVehicle,pricing);
        UUID activeRide=sql.queryForObject("INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id,status) VALUES(?,?,?,?,'ida',current_date,'18:00',4,600,1000,?,'active') RETURNING id",UUID.class,university,route,owner.id(),oldVehicle,pricing);
        for(int i=0;i<2;i++) {
            var passenger=user(false);
            UUID subscription=sql.queryForObject("INSERT INTO subscriptions(routine_id,driver_id,passenger_id,class_id,start_date,end_date,weekdays,directions,total_cents,discount_bps,status) VALUES(?,?,?,?,current_date,current_date+30,ARRAY[1,2]::smallint[],'ida',600,1000,'active') RETURNING id",UUID.class,routine,owner.id(),passenger.id(),classId);
            sql.update("INSERT INTO subscription_schedule(subscription_id,ride_id,cents,held) VALUES(?,?,600,true)",subscription,ride);
        }
        UUID tooSmall=apply(owner,application(owner,1),key());String retryKey=key();code(review(admin,tooSmall,true,retryKey),409,"VEHICLE_TOO_SMALL");
        assertEquals("pending",sql.queryForObject("SELECT status FROM driver_applications WHERE id=?",String.class,tooSmall));
        assertEquals("approved",sql.queryForObject("SELECT status FROM vehicles WHERE id=?",String.class,oldVehicle));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=? AND key=?",Integer.class,admin.id(),retryKey));
        UUID suitable=apply(owner,application(owner,3),key());assertEquals(200,review(admin,suitable,true,key()).statusCode());
        UUID newVehicle=sql.queryForObject("SELECT vehicle_id FROM driver_applications WHERE id=?",UUID.class,suitable);
        assertEquals("retired",sql.queryForObject("SELECT status FROM vehicles WHERE id=?",String.class,oldVehicle));
        assertEquals(newVehicle,sql.queryForObject("SELECT vehicle_id FROM rides WHERE id=?",UUID.class,ride));
        assertEquals(3,sql.queryForObject("SELECT capacity FROM rides WHERE id=?",Integer.class,ride));assertEquals(2,sql.queryForObject("SELECT version FROM rides WHERE id=?",Integer.class,ride));
        assertEquals(newVehicle,sql.queryForObject("SELECT vehicle_id FROM routines WHERE id=?",UUID.class,routine));
        assertEquals(oldVehicle,sql.queryForObject("SELECT vehicle_id FROM rides WHERE id=?",UUID.class,activeRide));
        assertEquals(4,sql.queryForObject("SELECT capacity FROM rides WHERE id=?",Integer.class,activeRide));
    }
    @Test void swaggerShowsAllNewOperationsAndCriticalHeaders() throws Exception {
        var api=tree(send("GET","/api/docs/json",null,null,null));var paths=api.get("paths");
        var list=paths.get("/api/v1/notifications").get("get");var params=new HashSet<String>();list.get("parameters").forEach(p -> params.add(p.get("name").asText()));
        assertTrue(params.containsAll(Set.of("limit","offset")));
        var apply=paths.get("/api/v1/drivers/applications").get("post");var headers=new HashSet<String>();apply.get("parameters").forEach(p -> { if(p.path("required").asBoolean()) headers.add(p.get("name").asText()); });
        assertTrue(headers.containsAll(Set.of("Origin","x-csrf-token","idempotency-key")));
        assertTrue(paths.has("/api/v1/admin/academic-verifications/{id}/review"));
        assertTrue(paths.has("/api/v1/admin/drivers/applications/{id}/review"));
        var controller=app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        assertEquals(132,controller.getHandlerMethods().entrySet().stream().filter(e -> e.getValue().getBeanType().getPackageName().startsWith("com.unicarona") && !e.getKey().getPatternValues().contains("/")).mapToLong(e -> e.getKey().getPatternValues().size()).sum());
        var actual=new TreeSet<String>();controller.getHandlerMethods().forEach((mapping,handler) -> { if(handler.getBeanType().getPackageName().startsWith("com.unicarona")) for(String path:mapping.getPatternValues()) if(!"/".equals(path)) for(var method:mapping.getMethodsCondition().getMethods()) actual.add(method.name()+" "+path); });
        var expected=new TreeSet<String>();var baseline=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/java-migration/api-baseline.json")));baseline.path("paths").fields().forEachRemaining(path -> path.getValue().fieldNames().forEachRemaining(method -> { if(Set.of("get","post","patch","delete","put","head","options").contains(method)) expected.add(method.toUpperCase(Locale.ROOT)+" "+path.getKey()); }));assertEquals(93,expected.size());
        expected.addAll(Set.of("GET /api/v1/payments/config","GET /api/v1/payments/{id}/summary","GET /api/v1/subscriptions/{id}/rights","GET /api/v1/wallet/debt","GET /api/v1/auth/email-service-status"));
        expected.addAll(Set.of("GET /api/v1/privacy/identity","POST /api/v1/privacy/identity/email","POST /api/v1/privacy/identity/email/confirm","POST /api/v1/privacy/identity/email/cancel","POST /api/v1/privacy/identity/birth"));
        expected.addAll(Set.of("GET /api/v1/admin/privacy/holds","POST /api/v1/admin/privacy/holds","POST /api/v1/admin/privacy/holds/{id}/review","POST /api/v1/admin/privacy/holds/{id}/evidence","POST /api/v1/admin/privacy/holds/{id}/data","GET /api/v1/privacy/retentions"));
        expected.addAll(Set.of("GET /api/v1/privacy/account","POST /api/v1/privacy/account/deactivate","POST /api/v1/privacy/account/reactivate","GET /api/v1/admin/privacy/accounts/{id}","POST /api/v1/admin/privacy/accounts/{id}/decision"));
        expected.addAll(Set.of("GET /api/v1/legal/documents","GET /api/v1/legal/documents/{slug}","GET /api/v1/legal/documents/{slug}/{version}",
            "GET /api/v1/privacy/legal","GET /api/v1/privacy/legal/history","POST /api/v1/privacy/legal",
            "GET /api/v1/privacy/closure","POST /api/v1/privacy/closure","POST /api/v1/privacy/closure/cancel",
            "GET /api/v1/privacy/preferences","PATCH /api/v1/privacy/preferences","GET /api/v1/privacy/requests","POST /api/v1/privacy/requests",
            "GET /api/v1/privacy/requests/{id}","POST /api/v1/privacy/export","GET /api/v1/admin/privacy/requests",
            "GET /api/v1/admin/privacy/requests/{id}","POST /api/v1/admin/privacy/requests/{id}/respond"));
        assertEquals(expected,actual,"Every peer HTTP method/path and the shared checkout/monthly-rights additions must exist in Spring");
        assertTrue(paths.path("/api/v1/payments/config").has("get"));
        assertTrue(paths.path("/api/v1/payments/{id}/summary").path("get").path("security").isArray());
        var rights=paths.path("/api/v1/subscriptions/{id}/rights").path("get");
        assertTrue(rights.path("security").isArray() && !rights.path("security").isEmpty());
        assertTrue(api.path("components").path("schemas").path("MonthlyContractRights").path("properties").has("openRights"));
    }
}
