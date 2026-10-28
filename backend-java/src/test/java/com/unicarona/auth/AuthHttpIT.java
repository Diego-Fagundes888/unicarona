package com.unicarona.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.auth.service.AuthService;
import com.unicarona.auth.service.AuthTransactions;
import com.unicarona.security.Actor;
import com.unicarona.security.CryptoService;
import com.unicarona.support.AuthTestSettings;
import com.unicarona.support.EncryptedMailFixture;
import com.unicarona.support.RealPostgres;
import com.unicarona.support.JavaApplicationPeer;
import com.unicarona.shared.ApiException;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/Security/JPA/transactions, with a real PostgreSQL and no service mocks. */
class AuthHttpIT {
    private static RealPostgres database;
    private static ServletWebServerApplicationContext app;
    private static JdbcTemplate sql;
    private static CryptoService crypto;
    private static String server;
    private static UUID university, course;
    private static final String PASSWORD="Synthetic password 2026", NEXT="Synthetic next password 2026";
    private final HttpClient http=HttpClient.newHttpClient();
    private final ObjectMapper json=new ObjectMapper();
    private record UserFixture(UUID id,String email) {}
    private record Session(Map<String,String> cookies,UUID user) {
        String csrf() { return cookies.get("uc_csrf"); }
        @Override public String toString() { return "Session[redacted]"; }
    }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres(); database.resetSchema();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(AuthTestSettings.arguments(database.apiConnectionUrl()));
        server="http://127.0.0.1:"+app.getWebServer().getPort(); sql=app.getBean(JdbcTemplate.class); crypto=app.getBean(CryptoService.class);
        com.unicarona.support.LegalTestFixtures.publish(app);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('auth-university','Auth University','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'auth-course','Auth Course') RETURNING id",UUID.class,university);
    }
    @BeforeEach void rates() { sql.update("DELETE FROM rate_limits"); }
    @AfterAll static void stop() { if(app!=null) app.close(); if(database!=null) database.close(); }
    private HttpResponse<String> request(String method,String path,Object body,Session session,boolean csrf,String origin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(server+path));
        if(origin!=null) builder.header("Origin",origin);
        if(session!=null) {
            builder.header("Cookie",String.join("; ",session.cookies().entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList()));
            if(csrf) builder.header("x-csrf-token",session.csrf());
        }
        if(body!=null) builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path,Object body,Session session) throws Exception {
        return request("POST","/api/v1/auth/"+path,body,session,true,AuthTestSettings.ORIGIN);
    }
    private JsonNode tree(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private Map<String,Object> registration(String email) {
        var academic=new LinkedHashMap<String,Object>();
        academic.put("universityId",university.toString()); academic.put("courseId",course.toString()); academic.put("region"," Gama ");
        academic.put("periodStatus","numbered"); academic.put("periodNumber",2); academic.put("shift","night"); academic.put("classLabel","A");
        var input=new LinkedHashMap<String,Object>(); input.put("name"," Synthetic Student "); input.put("email",email);
        input.put("password",PASSWORD); input.put("birth","2000-04-12"); input.put("phone","(61) 99999-1234"); input.put("truth",true); input.put("terms",true); input.put("academic",academic);
        input.put("legal",com.unicarona.support.LegalTestFixtures.registration(app));return input;
    }
    private String emailToken(UUID user,String kind) throws Exception {
        String payload=sql.queryForObject("SELECT o.payload::text FROM auth_tokens t JOIN outbox_jobs o ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind=? AND t.used_at IS NULL",String.class,user,kind);
        var parsed=json.readTree(payload);
        assertFalse(parsed.has("url")); assertEquals(kind,parsed.get("kind").asText());
        String url=EncryptedMailFixture.url(app,sql,user,kind);
        assertTrue(url.startsWith(AuthTestSettings.ORIGIN+"/login.html#")); return url.substring(url.indexOf("?token=")+7);
    }
    private UserFixture create(boolean verified) throws Exception {
        String email="student-"+UUID.randomUUID()+"@example.test";
        assertEquals(202,post("register",registration(email),null).statusCode());
        UUID id=sql.queryForObject("SELECT id FROM users WHERE email=?",UUID.class,email);
        if(verified) assertEquals(200,post("verify-email",Map.of("token",emailToken(id,"verify_email")),null).statusCode());
        return new UserFixture(id,email);
    }
    private Session login(UserFixture user) throws Exception {
        var response=post("login",Map.of("email",user.email(),"password",PASSWORD),null); assertEquals(200,response.statusCode());
        return session(response,user.id());
    }
    private Session session(HttpResponse<String> response,UUID user) {
        var cookies=new HashMap<String,String>();
        for(String header:response.headers().allValues("set-cookie")) {
            String[] pair=header.split(";",2)[0].split("=",2); cookies.put(pair[0],pair[1]);
            assertTrue(header.contains("Path=/")); assertTrue(header.contains("SameSite=Strict"));
            assertEquals(!"uc_csrf".equals(pair[0]),header.contains("HttpOnly"));
        }
        assertEquals(Set.of("uc_access","uc_refresh","uc_csrf"),cookies.keySet()); return new Session(Map.copyOf(cookies),user);
    }
    private void code(HttpResponse<String> response,int status,String code) throws Exception {
        assertEquals(status,response.statusCode()); var error=tree(response); assertEquals(code,error.get("code").asText());
        assertEquals(response.headers().firstValue("x-request-id").orElseThrow(),error.get("requestId").asText());
    }
    @Test void registrationNormalizesIdentityAndKeepsConfirmationGeneric() throws Exception {
        var user=create(false);
        assertEquals("Synthetic Student",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,user.id()));
        assertEquals("61999991234",sql.queryForObject("SELECT phone FROM users WHERE id=?",String.class,user.id()));
        assertEquals("Gama",sql.queryForObject("SELECT region_label FROM student_profiles WHERE user_id=?",String.class,user.id()));
        var duplicate=registration(user.email().toUpperCase(Locale.ROOT));
        @SuppressWarnings("unchecked") var academic=(Map<String,Object>)duplicate.get("academic"); academic.put("courseId",course.toString());
        assertEquals(202,post("register",duplicate,null).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id=?",Integer.class,user.id()));
        code(post("login",Map.of("email",user.email(),"password",PASSWORD),null),403,"EMAIL_UNVERIFIED");
        String token=emailToken(user.id(),"verify_email"); assertEquals(200,post("verify-email",Map.of("token",token),null).statusCode());
        code(post("verify-email",Map.of("token",token),null),400,"TOKEN_USED");
        assertEquals(200,post("login",Map.of("email",user.email().toUpperCase(Locale.ROOT),"password",PASSWORD),null).statusCode());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='account_registered'",Integer.class,user.id()));
    }
    @Test void loginJwtAndOwnerProfilePreserveExactFieldsAndCookies() throws Exception {
        var user=create(true); var session=login(user);
        assertEquals(3,session.cookies().get("uc_access").split("\\.").length);
        var response=request("GET","/api/v1/users/me",null,session,false,null); assertEquals(200,response.statusCode());
        var profile=tree(response); assertEquals(user.id().toString(),profile.get("id").asText()); assertEquals("A",profile.get("classLabel").asText());
        assertTrue(profile.get("emailVerified").asBoolean()); assertEquals("night",profile.get("shift").asText());
        assertEquals("user",profile.get("role").asText()); assertEquals("daily",profile.get("settings").get("withdrawal").asText());
        var expected=Set.of("id","name","email","birth","phone","role","accountStatus","emailVerified","universityId","university","courseId","course","periodStatus","periodNumber","classLabel","classId","shift","regionId","region","verificationStatus","driverStatus","photo","settings");
        var fields=new HashSet<String>(); profile.fieldNames().forEachRemaining(fields::add); assertEquals(expected,fields);
        assertEquals(profile,tree(request("GET","/api/v1/auth/me",null,session,false,null)));
        assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
    }
    @Test void passwordRecoveryUsesSingleUseEncryptedTokensAndRevokesEverySession() throws Exception {
        var user=create(true); var a=login(user); var b=login(user);
        var known=post("forgot-password",Map.of("email",user.email()),null);
        assertEquals(known.body(),post("forgot-password",Map.of("email","missing@example.test"),null).body());
        String token=emailToken(user.id(),"reset_password");
        assertEquals(200,post("reset-password",Map.of("token",token,"password",NEXT),null).statusCode());
        code(post("reset-password",Map.of("token",token,"password",NEXT),null),400,"TOKEN_INVALID");
        code(request("GET","/api/v1/auth/me",null,a,false,null),401,"SESSION_EXPIRED");
        code(request("GET","/api/v1/auth/me",null,b,false,null),401,"SESSION_EXPIRED");
        code(post("login",Map.of("email",user.email(),"password",PASSWORD),null),401,"INVALID_CREDENTIALS");
        assertEquals(200,post("login",Map.of("email",user.email(),"password",NEXT),null).statusCode());
    }
    @Test void refreshRotationAndReuseRevokeTheFamilyEvenWhenReturning401() throws Exception {
        var user=create(true); var peer=login(user);
        code(request("POST","/api/v1/auth/refresh",Map.of(),peer,false,AuthTestSettings.ORIGIN),401,"SESSION_EXPIRED");
        var response=post("refresh",Map.of(),peer); assertEquals(200,response.statusCode()); var child=session(response,user.id());
        code(request("GET","/api/v1/auth/me",null,peer,false,null),401,"SESSION_EXPIRED");
        assertEquals(200,request("GET","/api/v1/auth/me",null,child,false,null).statusCode());
        code(post("refresh",Map.of(),peer),401,"SESSION_REUSED");
        code(request("GET","/api/v1/auth/me",null,child,false,null),401,"SESSION_EXPIRED");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=? AND revoked_at IS NULL",Integer.class,user.id()));
    }
    @Test void originCsrfAndLogoutAreEnforcedWithoutTrustingProxyHeaders() throws Exception {
        var user=create(true); var session=login(user);
        code(request("POST","/api/v1/auth/logout",Map.of(),session,true,null),403,"ORIGIN_INVALID");
        code(request("POST","/api/v1/auth/logout",Map.of(),session,true,"https://foreign.example"),403,"ORIGIN_INVALID");
        code(request("POST","/api/v1/auth/logout",Map.of(),session,false,AuthTestSettings.ORIGIN),403,"CSRF_INVALID");
        var response=post("logout",Map.of(),session); assertEquals(200,response.statusCode());
        assertEquals(3,response.headers().allValues("set-cookie").size());
        assertTrue(response.headers().allValues("set-cookie").stream().allMatch(h -> h.contains("Max-Age=0")));
        code(request("GET","/api/v1/auth/me",null,session,false,null),401,"SESSION_EXPIRED");
    }
    @Test void passwordChangeKeepsRestrictedAccountsAbleToSecureTheirAccount() throws Exception {
        var user=create(true); var session=login(user);
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",user.id());
        assertEquals(200,request("GET","/api/v1/auth/me",null,session,false,null).statusCode());
        code(post("change-password",Map.of("currentPassword","wrong","password",NEXT),session),401,"INVALID_CREDENTIALS");
        assertEquals(200,post("change-password",Map.of("currentPassword",PASSWORD,"password",NEXT),session).statusCode());
        code(request("GET","/api/v1/auth/me",null,session,false,null),401,"SESSION_EXPIRED");
        assertEquals(200,post("login",Map.of("email",user.email(),"password",NEXT),null).statusCode());
    }
    @Test void legacyOpaqueSessionsWorkAndRolesComeFromCurrentDatabaseState() throws Exception {
        var user=create(true); String access=crypto.randomToken(),refresh=crypto.randomToken(),csrf=crypto.randomToken();
        sql.update("INSERT INTO sessions(user_id,access_hash,refresh_hash,csrf_hash,family_id,access_expires_at,refresh_expires_at) VALUES(?,?,?,?,?,now()+interval '15 minutes',now()+interval '30 days')",
            user.id(),crypto.hmac(access,AuthTestSettings.PEPPER),crypto.hmac(refresh,AuthTestSettings.PEPPER),crypto.hmac(csrf,AuthTestSettings.PEPPER),UUID.randomUUID());
        var session=new Session(Map.of("uc_access",access,"uc_refresh",refresh,"uc_csrf",csrf),user.id());
        assertEquals(200,request("GET","/api/v1/auth/me",null,session,false,null).statusCode());
        var auth=app.getBean(AuthService.class); assertEquals(Set.of("ROLE_PASSENGER"),auth.authenticate(access).authorities());
        sql.update("INSERT INTO driver_profiles(user_id,status) VALUES(?,'pending')",user.id());
        assertFalse(auth.authenticate(access).authorities().contains("ROLE_DRIVER"));
        sql.update("UPDATE driver_profiles SET status='approved',approved_at=now() WHERE user_id=?",user.id());
        assertTrue(auth.authenticate(access).authorities().contains("ROLE_DRIVER"));
        code(request("GET","/api/v1/admin/academic-verifications",null,session,false,null),403,"FORBIDDEN");
        sql.update("UPDATE users SET role='admin' WHERE id=?",user.id()); assertTrue(auth.authenticate(access).authorities().contains("ROLE_ADMIN"));
        var response=post("refresh",Map.of(),session); assertEquals(200,response.statusCode());
        assertEquals(3,session(response,user.id()).cookies().get("uc_access").split("\\.").length);
    }
    @Test void expiredDeletedAndForgedCredentialsCannotAuthenticate() throws Exception {
        var user=create(true); var session=login(user);
        var forged=new HashMap<>(session.cookies()); String jwt=forged.get("uc_access"); forged.put("uc_access",jwt.substring(0,jwt.length()-2)+"xx");
        code(request("GET","/api/v1/auth/me",null,new Session(forged,user.id()),false,null),401,"SESSION_EXPIRED");
        sql.update("UPDATE sessions SET access_expires_at=now()-interval '1 second' WHERE user_id=?",user.id());
        code(request("GET","/api/v1/auth/me",null,session,false,null),401,"SESSION_EXPIRED");
        var fresh=login(user); sql.update("UPDATE users SET deleted_at=now(),account_status='deleted' WHERE id=?",user.id());
        code(request("GET","/api/v1/auth/me",null,fresh,false,null),401,"SESSION_EXPIRED");
        code(post("refresh",Map.of(),fresh),401,"SESSION_EXPIRED");
    }
    @Test void validationRejectsUnknownFieldsCoercionAndInconsistentAcademicData() throws Exception {
        String email="invalid-"+UUID.randomUUID()+"@example.test";
        var body=registration(email); body.put("role","admin"); code(post("register",body,null),422,"VALIDATION_ERROR");
        body.remove("role"); body.put("truth",false); code(post("register",body,null),422,"VALIDATION_ERROR");
        body.put("truth",true); body.put("birth","2026-02-30"); code(post("register",body,null),422,"VALIDATION_ERROR");
        body.put("birth","2000-04-12");
        @SuppressWarnings("unchecked") var academic=(Map<String,Object>)body.get("academic");
        academic.put("periodNumber","2"); code(post("register",body,null),422,"VALIDATION_ERROR");
        academic.put("periodNumber",2.5); code(post("register",body,null),422,"VALIDATION_ERROR");
        academic.remove("periodNumber"); code(post("register",body,null),422,"VALIDATION_ERROR");
        academic.put("periodNumber",2); academic.remove("shift"); code(post("register",body,null),422,"VALIDATION_ERROR");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email));
    }
    @Test void registrationWithoutPhoneKeepsTheContactAbsentAndRejectsMalformedSuppliedNumbers() throws Exception {
        String email="no-phone-"+UUID.randomUUID()+"@example.test";var body=registration(email);body.put("phone","");
        assertEquals(202,post("register",body,null).statusCode());
        UUID owner=sql.queryForObject("SELECT id FROM users WHERE email=?",UUID.class,email);
        assertNull(sql.queryForObject("SELECT phone FROM users WHERE id=?",String.class,owner));
        assertEquals(5,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,owner));
        String invalidEmail="bad-phone-"+UUID.randomUUID()+"@example.test";var malformed=registration(invalidEmail);malformed.put("phone","123");
        code(post("register",malformed,null),422,"VALIDATION_ERROR");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,invalidEmail));
    }
    @Test void optionalNonNullableInputsAndEmailUuidFormatsMatchTheOriginalValidator() throws Exception {
        var body=registration("format-"+UUID.randomUUID()+"@example.test");
        @SuppressWarnings("unchecked") var academic=(Map<String,Object>)body.get("academic");
        body.put("birthValid",true); code(post("register",body,null),422,"VALIDATION_ERROR"); body.remove("birthValid");
        body.put("phone",null); code(post("register",body,null),422,"VALIDATION_ERROR"); body.remove("phone");
        academic.put("regionId",null); code(post("register",body,null),422,"VALIDATION_ERROR"); academic.remove("regionId");
        academic.put("courseId","11111111-1111-0111-1111-111111111111"); code(post("register",body,null),422,"VALIDATION_ERROR"); academic.put("courseId",course.toString());
        body.put("email","a@b"); code(post("register",body,null),422,"VALIDATION_ERROR");
        body.put("email","format-"+UUID.randomUUID()+"@example.test"); body.put("name","\u00a0Synthetic Student\ufeff");
        assertEquals(202,post("register",body,null).statusCode());
        assertEquals("Synthetic Student",sql.queryForObject("SELECT name FROM users WHERE email=?",String.class,body.get("email")));
    }
    @Test void swaggerIncludesCookieSecurityRequiredHeadersAndErrorResponses() throws Exception {
        var api=tree(request("GET","/api/docs/json",null,null,false,null));
        var paths=api.get("paths"); var register=paths.get("/api/v1/auth/register").get("post");
        assertTrue(register.get("responses").has("202")); assertTrue(register.get("responses").has("422"));
        var logout=paths.get("/api/v1/auth/logout").get("post"); assertTrue(logout.get("security").get(0).has("cookieAuth"));
        var headers=new HashSet<String>(); logout.get("parameters").forEach(p -> { if(p.get("required").asBoolean()) headers.add(p.get("name").asText()); });
        assertEquals(Set.of("Origin","x-csrf-token"),headers);
        assertEquals("uc_access",api.get("components").get("securitySchemes").get("cookieAuth").get("name").asText());
        assertFalse(api.get("components").get("schemas").get("Register").get("properties").has("birthValid"));
    }
    @Test void resendInvalidatesThePreviousLinkAndExpiredLinksCannotBeConsumed() throws Exception {
        var user=create(false); String peer=emailToken(user.id(),"verify_email"); sql.update("DELETE FROM rate_limits");
        assertEquals(200,post("resend-verification",Map.of("email",user.email()),null).statusCode());
        code(post("verify-email",Map.of("token",peer),null),400,"TOKEN_REPLACED");
        String current=emailToken(user.id(),"verify_email");
        sql.update("UPDATE auth_tokens SET expires_at=now()-interval '1 second' WHERE user_id=? AND used_at IS NULL",user.id());
        code(post("verify-email",Map.of("token",current),null),400,"TOKEN_EXPIRED");
        assertFalse(sql.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id=?",Boolean.class,user.id()));
    }
    @Test void invalidAcademicRegistrationRollsBackAllIdentityTokenAndOutboxWrites() throws Exception {
        int jobsBefore=sql.queryForObject("SELECT count(*) FROM outbox_jobs",Integer.class);
        String email="rollback-"+UUID.randomUUID()+"@example.test"; var body=registration(email);
        @SuppressWarnings("unchecked") var academic=(Map<String,Object>)body.get("academic"); academic.put("courseId",UUID.randomUUID().toString());
        code(post("register",body,null),422,"INVALID_COURSE");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email));
        assertEquals(jobsBefore,sql.queryForObject("SELECT count(*) FROM outbox_jobs",Integer.class));
        academic.put("courseId",course.toString()); body.put("birth",java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).minusYears(17).toString());
        code(post("register",body,null),422,"UNDERAGE");
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email));
    }
    @Test void repeatedInvalidLoginHitsThePersistentLimitAndOriginIsRequiredForPublicMutations() throws Exception {
        code(request("POST","/api/v1/auth/login",Map.of("email","missing-limit@example.test","password",PASSWORD),null,false,null),403,"ORIGIN_INVALID");
        for(int i=0;i<10;i++) code(post("login",Map.of("email","missing-limit@example.test","password",PASSWORD),null),401,"INVALID_CREDENTIALS");
        code(post("login",Map.of("email","missing-limit@example.test","password",PASSWORD),null),429,"RATE_LIMITED");
        assertTrue(sql.queryForObject("SELECT bool_and(bucket ~ '^[a-f0-9]{64}$') FROM rate_limits",Boolean.class));
    }
    @Test void concurrentRefreshReuseCommitsRevocationAndLogoutCannotMissAChild() throws Exception {
        var user=create(true); var peer=login(user); var auth=app.getBean(AuthService.class);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<String> operation=() -> { gate.await(); try { auth.refresh(peer.cookies().get("uc_refresh"),peer.csrf()); return "ok"; } catch(ApiException e) { return e.code(); } };
            var a=executor.submit(operation); var b=executor.submit(operation); gate.countDown();
            assertEquals(Set.of("ok","SESSION_REUSED"),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=? AND revoked_at IS NULL",Integer.class,user.id()));
            var next=login(user); Actor actor=auth.authenticate(next.cookies().get("uc_access")); var secondGate=new CountDownLatch(1);
            var refresh=executor.submit(() -> { secondGate.await(); try { auth.refresh(next.cookies().get("uc_refresh"),next.csrf()); } catch(ApiException e) { assertEquals("SESSION_REUSED",e.code()); } return true; });
            var logout=executor.submit(() -> { secondGate.await(); auth.logout(actor); return true; }); secondGate.countDown();
            assertTrue(refresh.get(10,TimeUnit.SECONDS)); assertTrue(logout.get(10,TimeUnit.SECONDS));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=? AND revoked_at IS NULL",Integer.class,user.id()));
        }
    }
    @Test void aPasswordResetBetweenHashVerificationAndSessionCreationCannotIssueASession() throws Exception {
        var user=create(true); String old=sql.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,user.id());
        sql.update("UPDATE users SET password_hash=? WHERE id=?",crypto.hashPassword(NEXT),user.id());
        var error=assertThrows(ApiException.class,() -> app.getBean(AuthTransactions.class).login(user.id(),old));
        assertEquals("INVALID_CREDENTIALS",error.code());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Integer.class,user.id()));
    }
    @Test void independentJavaInstancesAuthReturnEquivalentResponsesAndAcceptEachOthersRegisteredCookies() throws Exception {
        var user=create(true); var javaSession=login(user);
        try(var peer=new JavaApplicationPeer(app, database.apiConnectionUrl())) {
            var headers=Map.of("origin",AuthTestSettings.ORIGIN);
            for(String path:List.of("forgot-password","resend-verification")) {
                var body=Map.of("email","parity-missing@example.test");
                var old=peer.request("POST","/api/v1/auth/"+path,body,headers); var current=post(path,body,null);
                if(path.equals("resend-verification")) { assertEquals(200,old.path("status").asInt()); assertEquals(429,current.statusCode()); assertEquals("EMAIL_COOLDOWN",tree(current).path("code").asText()); } else { assertEquals(200,current.statusCode()); assertEquals(old.get("body").path("message"),tree(current).path("message")); }
            }
            var duplicate=registration(user.email());
            // Both instances enforce the same versioned legal evidence.
            var duplicateInput=new LinkedHashMap<>(duplicate);
            var oldDuplicate=peer.request("POST","/api/v1/auth/register",duplicateInput,headers);
            var duplicateResponse=post("register",duplicate,null); assertEquals(oldDuplicate.get("status").asInt(),duplicateResponse.statusCode());
            assertEquals(oldDuplicate.get("body").path("message"),tree(duplicateResponse).path("message")); assertEquals(60,tree(duplicateResponse).path("retryAfterSeconds").asInt());
            var body=Map.of("email",user.email(),"password",PASSWORD);
            var oldLogin=peer.request("POST","/api/v1/auth/login",body,headers); var current=post("login",body,null);
            assertEquals(oldLogin.get("status").asInt(),current.statusCode()); assertEquals(oldLogin.get("body"),tree(current));
            var oldCookies=new HashMap<String,String>();
            for(JsonNode cookie:oldLogin.get("cookies")) oldCookies.put(cookie.get("name").asText(),cookie.get("value").asText());
            var oldSession=new Session(Map.copyOf(oldCookies),user.id());
            // A session created by either Java instance is accepted by the other.
            assertEquals(oldLogin.get("body").get("profile"),tree(request("GET","/api/v1/auth/me",null,oldSession,false,null)));
            var javaCookie=String.join("; ",javaSession.cookies().entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList());
            var oldMe=peer.request("GET","/api/v1/auth/me",null,Map.of("cookie",javaCookie));
            assertEquals(200,oldMe.get("status").asInt()); assertEquals(tree(request("GET","/api/v1/auth/me",null,javaSession,false,null)),oldMe.get("body"));
            var oldRefresh=peer.request("POST","/api/v1/auth/refresh",Map.of(),Map.of("origin",AuthTestSettings.ORIGIN,"cookie",javaCookie,"x-csrf-token",javaSession.csrf()));
            var newRefresh=post("refresh",Map.of(),oldSession);
            assertEquals(oldRefresh.get("status").asInt(),newRefresh.statusCode()); assertEquals(oldRefresh.get("body"),tree(newRefresh));
            var oldBad=peer.request("POST","/api/v1/auth/login",Map.of("email",user.email(),"password","incorrect"),headers);
            var javaBad=post("login",Map.of("email",user.email(),"password","incorrect"),null);
            assertEquals(oldBad.get("status").asInt(),javaBad.statusCode());
            var oldError=oldBad.get("body").deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)oldError).remove("requestId");
            var newError=tree(javaBad).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)newError).remove("requestId"); assertEquals(oldError,newError);
        }
    }
}
