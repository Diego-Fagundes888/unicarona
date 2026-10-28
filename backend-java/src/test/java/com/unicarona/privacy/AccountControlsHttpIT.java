package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/PostgreSQL, synthetic users, private temporary erasure journal, no external email or payments. */
class AccountControlsHttpIT {
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;
    private static String server,hash;private static UUID university,course;private static Path storage,journal;
    private static final String PASSWORD="Synthetic account control password 2026";
    private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String email,String cookie,String csrf){@Override public String toString(){return "AccountControlSession[redacted]";}}
    @BeforeAll static void start()throws Exception {
        db=new RealPostgres();db.resetSchema();storage=Files.createTempDirectory("unicarona-account-controls-storage-");journal=Files.createTempDirectory("unicarona-account-controls-journal-");
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));args.add("--STORAGE_PATH="+storage);args.add("--PRIVACY_ERASURE_JOURNAL_PATH="+journal);
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();hash=app.getBean(CryptoService.class).hashPassword(PASSWORD);LegalTestFixtures.publish(app);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('account-control-university','Synthetic university','Synthetic city','GO') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'synthetic','Synthetic course') RETURNING id",UUID.class,university);
    }
    @AfterAll static void close()throws Exception {if(app!=null)app.close();if(db!=null)db.close();for(Path root:List.of(storage,journal)){assertTrue(root.getFileName().toString().startsWith("unicarona-account-controls-"));try(var walk=Files.walk(root)){for(var path:walk.sorted(Comparator.reverseOrder()).toList()){assertTrue(path.normalize().startsWith(root.normalize()));Files.delete(path);}}}}
    @BeforeEach void limits(){sql.update("DELETE FROM rate_limits");}
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");if(session!=null)request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());if(key!=null)request.header("idempotency-key",key);
        request.method(method,"GET".equals(method)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    private JsonNode ok(HttpResponse<String> response)throws Exception {assertEquals(200,response.statusCode(),response.body());return tree(response);}
    private Session login(UUID id,String email)throws Exception {var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);ok(response);var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")){var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}return new Session(id,email,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));}
    private Session user(boolean admin)throws Exception {String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at,role) VALUES(?,?,'Synthetic account user','2000-01-01',now(),?) RETURNING id",UUID.class,email,hash,admin?"admin":"user");return login(id,email);}
    private String key(){return UUID.randomUUID().toString();}
    private String decision(Session user){return "/api/v1/admin/privacy/accounts/"+user.id()+"/decision";}
    private Map<String,Object> decisionBody(String action,String expected){var body=new LinkedHashMap<String,Object>(Map.of("password",PASSWORD,"action",action,"expectedStatus",expected,"reasonCode","security","publicReason","Synthetic public reason for the account decision.","reason","Synthetic private investigation notes; do not disclose to another participant."));if("ban".equals(action))body.put("restrictionDays",90);return body;}
    private JsonNode decide(Session administrator,Session user,String action,String expected)throws Exception{return ok(send("POST",decision(user),decisionBody(action,expected),administrator,key()));}
    private JsonNode deactivate(Session user)throws Exception{return ok(send("POST","/api/v1/privacy/account/deactivate",Map.of("password",PASSWORD,"acknowledged",true),user,key()));}
    private JsonNode account(Session user)throws Exception{return ok(send("GET","/api/v1/privacy/account",null,user,null));}
    private Map<String,Object> registration(String email){return Map.of("email",email,"name","Synthetic replacement user","password",PASSWORD,"birth","2000-01-01","terms",true,"truth",true,"legal",LegalTestFixtures.registration(app),"academic",Map.of("universityId",university.toString(),"courseId",course.toString(),"region","Synthetic region","periodStatus","numbered","periodNumber",1,"shift","night"));}
    private void eraseAfterGrace(Session user){sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE user_id=? AND status IN ('scheduled','pending_obligations')",user.id());app.getBean(PrivacyErasureMaintenance.class).run(100,()->false);}
    @Test void administrativeDecisionIdentifiesItsSubjectWithoutExposingDeletedRawIdentityOrAnotherUsersAccess()throws Exception {
        var administrator=user(true);var a=user(false);var peer=user(false);String path="/api/v1/admin/privacy/accounts/"+a.id();sql.update("UPDATE users SET name='Synthetic target with multiple surnames',phone='61999999999' WHERE id=?",a.id());
        assertEquals(401,send("GET",path,null,null,null).statusCode());assertEquals(403,send("GET",path,null,peer,null).statusCode());var response=send("GET",path,null,administrator,null);var detail=ok(response);var subject=detail.path("subject");
        assertEquals(a.id().toString(),subject.path("id").asText());assertEquals("Synthetic target with multiple surnames",subject.path("name").asText());assertEquals(a.email(),subject.path("email").asText());var fields=new HashSet<String>();subject.fieldNames().forEachRemaining(fields::add);assertEquals(Set.of("id","name","email"),fields);assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
        sql.update("UPDATE users SET account_status='deleted',deleted_at=now(),password_hash='disabled' WHERE id=?",a.id());var deleted=ok(send("GET",path,null,administrator,null));assertEquals("Conta excluída",deleted.path("subject").path("name").asText());assertEquals("",deleted.path("subject").path("email").asText());assertFalse(deleted.toString().contains(a.email()));assertFalse(deleted.toString().contains("Synthetic target with multiple surnames"));
        assertEquals("AdministrativeAccountSubject[redacted]",new com.unicarona.privacy.dto.AccountControlDto.Subject(a.id(),"Synthetic private","synthetic@example.test").toString());
    }
    @Test void deactivationNeedsPasswordCsrfAcknowledgementAndCannotChooseAnotherAccount()throws Exception {
        var a=user(false);var b=user(false);var body=new LinkedHashMap<String,Object>(Map.of("password",PASSWORD,"acknowledged",true));
        assertEquals(401,send("POST","/api/v1/privacy/account/deactivate",Map.of("password","incorrect","acknowledged",true),a,key()).statusCode());
        assertEquals(422,send("POST","/api/v1/privacy/account/deactivate",Map.of("password",PASSWORD,"acknowledged",false),a,key()).statusCode());
        assertEquals(403,send("POST","/api/v1/privacy/account/deactivate",body,new Session(a.id(),a.email(),a.cookie(),"invalid"),key()).statusCode());
        body.put("userId",b.id().toString());assertEquals(422,send("POST","/api/v1/privacy/account/deactivate",body,a,key()).statusCode());
        var state=deactivate(a);assertEquals("deactivated",state.path("status").asText());assertTrue(state.path("canReactivate").asBoolean());assertEquals("active",account(b).path("status").asText());assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_account_closures WHERE user_id=?",Integer.class,a.id()));assertNull(sql.queryForObject("SELECT deleted_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));
    }
    @Test void deactivationRevokesOtherSessionsAndReactivationDoesNotRestoreMarketingOrEraseARestriction()throws Exception {
        var a=user(false);var other=login(a.id(),a.email());String version=ok(send("GET","/api/v1/privacy/preferences",null,a,null)).path("statementVersion").asText();ok(send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",true,"statementVersion",version),a,null));
        deactivate(a);assertEquals(401,send("GET","/api/v1/auth/me",null,other,null).statusCode());assertEquals(403,send("GET","/api/v1/rides",null,a,null).statusCode());
        var back=login(a.id(),a.email());assertEquals("deactivated",account(back).path("status").asText());
        assertEquals("active",ok(send("POST","/api/v1/privacy/account/reactivate",Map.of("password",PASSWORD),back,key())).path("status").asText());assertFalse(ok(send("GET","/api/v1/privacy/preferences",null,back,null)).path("marketing").asBoolean());
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",a.id());assertEquals(409,send("POST","/api/v1/privacy/account/reactivate",Map.of("password",PASSWORD),back,key()).statusCode());
    }
    @Test void deactivatedUserCanRequestClosureAndCancelBackToDeactivation()throws Exception {
        var a=user(false);deactivate(a);String version=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();
        var requested=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),a,key());assertEquals(202,requested.statusCode(),requested.body());assertTrue(tree(requested).path("canCancel").asBoolean());
        ok(send("POST","/api/v1/privacy/closure/cancel",Map.of("password",PASSWORD),a,key()));assertEquals("deactivated",account(a).path("status").asText());
    }
    @Test void banIsAuthenticatedReasonedEncryptedAuditedAndLeavesAnAppealAvailable()throws Exception {
        var administrator=user(true);var a=user(false);var b=user(false);var body=decisionBody("ban","active");
        assertEquals(403,send("POST",decision(a),body,b,key()).statusCode());body.put("password","incorrect");assertEquals(401,send("POST",decision(a),body,administrator,key()).statusCode());body.put("password",PASSWORD);
        String key=key();var result=send("POST",decision(a),body,administrator,key);assertEquals("banned",ok(result).path("status").asText());assertEquals(result.body(),send("POST",decision(a),body,administrator,key).body());
        assertFalse(result.body().contains((String)body.get("reason")));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_account_decisions WHERE user_id=?",Integer.class,a.id()));assertFalse(sql.queryForObject("SELECT reason_ciphertext FROM privacy_account_decisions WHERE user_id=?",String.class,a.id()).contains("Synthetic private"));assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_account_decisions SET public_reason='Altered evidence' WHERE user_id=?",a.id()));
        assertEquals(401,send("GET","/api/v1/auth/me",null,a,null).statusCode());var banned=login(a.id(),a.email());var state=account(banned);assertFalse(state.path("closure").path("canCancel").asBoolean());assertEquals("platform",state.path("closure").path("initiatedBy").asText());assertTrue(state.path("restrictionUntil").asText().length()>10);
        assertEquals(409,send("POST","/api/v1/privacy/account/reactivate",Map.of("password",PASSWORD),banned,key()).statusCode());assertEquals(409,send("POST","/api/v1/privacy/closure/cancel",Map.of("password",PASSWORD),banned,key()).statusCode());
        assertEquals(202,send("POST","/api/v1/privacy/requests",Map.of("kind","appeal","details","Synthetic request to review the account decision."),banned,key()).statusCode());
        assertEquals(403,send("GET","/api/v1/admin/privacy/accounts/"+a.id(),null,b,null).statusCode());assertTrue(ok(send("GET","/api/v1/admin/privacy/accounts/"+a.id(),null,administrator,null)).path("decisions").get(0).path("internalReason").asText().contains("Synthetic private"));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='account_decision_evidence_accessed'",Integer.class,administrator.id()));
    }
    @Test void deletionDoesNotRemoveFiniteRejoinRestrictionAndExpiryDoesNotCreateARetroactiveAcceptance()throws Exception {
        var administrator=user(true);var a=user(false);decide(administrator,a,"ban","active");eraseAfterGrace(a);assertNotNull(sql.queryForObject("SELECT deleted_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));
        int jobs=sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE type='email'",Integer.class);var body=registration(a.email().toUpperCase(Locale.ROOT));assertEquals(202,send("POST","/api/v1/auth/register",body,null,null).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,a.email()));assertEquals(jobs,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE type='email'",Integer.class));
        var row=sql.queryForMap("SELECT email_hmac,expires_at<=created_at+interval '1 year' AS bounded FROM privacy_registration_restrictions WHERE source_user_id=?",a.id());assertTrue((Boolean)row.get("bounded"));assertFalse(row.get("email_hmac").toString().contains(a.email()));assertEquals(64,row.get("email_hmac").toString().length());
        sql.update("UPDATE privacy_registration_restrictions SET created_at=now()-interval '2 years',expires_at=now()-interval '1 year' WHERE source_user_id=?",a.id());app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_registration_restrictions WHERE source_user_id=?",Integer.class,a.id()));
        assertEquals(202,send("POST","/api/v1/auth/register",body,null,null).statusCode());UUID replacement=sql.queryForObject("SELECT id FROM users WHERE email=?",UUID.class,a.email());assertNotEquals(a.id(),replacement);assertEquals(5,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=? AND source='registration'",Integer.class,replacement));assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void liftingBanCancellingPlatformClosureAndRestoringServiceAreSeparateReviewedActions()throws Exception {
        var administrator=user(true);var a=user(false);decide(administrator,a,"ban","active");decide(administrator,a,"lift_ban","banned");
        assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_registration_restrictions WHERE source_user_id=? AND released_at IS NULL",Integer.class,a.id()));
        assertEquals(409,send("POST","/api/v1/admin/accounts/"+a.id()+"/review",Map.of("status","active","expectedStatus","suspended","reason","Synthetic review of service restoration."),administrator,key()).statusCode());
        decide(administrator,a,"cancel_closure","suspended");ok(send("POST","/api/v1/admin/accounts/"+a.id()+"/review",Map.of("status","active","expectedStatus","suspended","reason","Synthetic review of service restoration."),administrator,key()));
        assertEquals("active",account(login(a.id(),a.email())).path("status").asText());assertEquals(3,sql.queryForObject("SELECT count(*) FROM privacy_account_decisions WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void restrictionPeriodMustBeChosenForTheCaseAndCannotBeReusedWithAnotherIdempotentPayload()throws Exception {
        var administrator=user(true);var a=user(false);var body=decisionBody("ban","active");body.remove("restrictionDays");
        assertEquals(422,send("POST",decision(a),body,administrator,key()).statusCode());body.put("restrictionDays",366);assertEquals(422,send("POST",decision(a),body,administrator,key()).statusCode());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_account_decisions WHERE user_id=?",Integer.class,a.id()));
        body.put("restrictionDays",30);String key=key();ok(send("POST",decision(a),body,administrator,key));
        assertEquals(30,sql.queryForObject("SELECT restriction_days FROM privacy_account_decisions WHERE user_id=?",Integer.class,a.id()));assertTrue(sql.queryForObject("SELECT expires_at=created_at+interval '30 days' FROM privacy_registration_restrictions WHERE source_user_id=?",Boolean.class,a.id()));
        body.put("restrictionDays",90);assertEquals(409,send("POST",decision(a),body,administrator,key).statusCode());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_account_decisions WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void erasureAfterBanPreservesBalanceAndEncryptedMinimumContactWithoutKeepingTheProfileActive()throws Exception {
        var administrator=user(true);var a=user(false);var ledger=app.getBean(com.unicarona.payment.service.LedgerService.class);
        new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->ledger.post(com.unicarona.payment.ledger.LedgerCommand.transfer("synthetic-account-balance-"+key(),"synthetic_funding","Synthetic account control fixture",List.of(
            new com.unicarona.payment.ledger.LedgerCommand.Posting(null,com.unicarona.payment.ledger.AccountKind.PROVIDER_CLEARING,-500),
            new com.unicarona.payment.ledger.LedgerCommand.Posting(a.id(),com.unicarona.payment.ledger.AccountKind.PASSENGER_CREDIT,500)))));
        int entries=sql.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class);decide(administrator,a,"ban","active");eraseAfterGrace(a);
        assertNotNull(sql.queryForObject("SELECT deleted_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));assertEquals(500,ledger.balance(a.id(),com.unicarona.payment.ledger.AccountKind.PASSENGER_CREDIT));assertEquals(entries,sql.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));assertEquals(0,ledger.reconciliation().difference());
        var record=sql.queryForMap("SELECT id,content_ciphertext FROM privacy_retained_records WHERE user_id=? AND category='contract_identity'",a.id());String retained=(String)record.get("content_ciphertext");assertFalse(retained.contains(a.email()));assertTrue(app.getBean(com.unicarona.privacy.service.PrivacyEvidenceCrypto.class).open(com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,a.id(),(UUID)record.get("id"),retained).contains(a.email()));
        var evidence=ok(send("GET","/api/v1/admin/privacy/accounts/"+a.id(),null,administrator,null));assertEquals("deleted",evidence.path("account").path("status").asText());assertTrue(evidence.path("decisions").get(0).path("internalReason").asText().contains("Synthetic private"));assertFalse(evidence.toString().contains(a.email()));
        assertThrows(RuntimeException.class,()->sql.update("DELETE FROM privacy_account_decisions WHERE user_id=?",a.id()));assertEquals(401,send("GET","/api/v1/privacy/account",null,a,null).statusCode());
    }
    @Test void platformClosurePreservesEarlierDeadlineAndCannotBeCancelledByTheUserOrTargetAnAdmin()throws Exception {
        var administrator=user(true);var a=user(false);String version=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();var response=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),a,key());assertEquals(202,response.statusCode());String deadline=tree(response).path("eraseAfter").asText();
        var closed=decide(administrator,a,"close","closing");assertEquals(deadline,closed.path("closure").path("eraseAfter").asText());assertFalse(closed.path("closure").path("canCancel").asBoolean());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_account_closures WHERE user_id=?",Integer.class,a.id()));
        assertEquals(422,send("POST",decision(administrator),decisionBody("ban","active"),administrator,key()).statusCode());
    }
    @Test void reviewingASafetyRestrictionNeverSilentlyReactivatesAVoluntarilyDeactivatedAccount()throws Exception {
        var administrator=user(true);var a=user(false);deactivate(a);decide(administrator,a,"ban","deactivated");decide(administrator,a,"lift_ban","banned");decide(administrator,a,"cancel_closure","suspended");
        var restored=ok(send("POST","/api/v1/admin/accounts/"+a.id()+"/review",Map.of("status","active","expectedStatus","suspended","reason","Synthetic safety review with voluntary deactivation preserved."),administrator,key()));assertEquals("deactivated",restored.path("status").asText());
        var fresh=login(a.id(),a.email());assertEquals("deactivated",account(fresh).path("status").asText());assertNotNull(sql.queryForObject("SELECT voluntarily_deactivated_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));
        assertEquals("active",ok(send("POST","/api/v1/privacy/account/reactivate",Map.of("password",PASSWORD),fresh,key())).path("status").asText());assertNull(sql.queryForObject("SELECT voluntarily_deactivated_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));
    }
}
