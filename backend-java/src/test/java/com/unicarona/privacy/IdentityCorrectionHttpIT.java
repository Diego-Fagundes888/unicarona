package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.service.AuthService;
import com.unicarona.job.service.OutboxProcessor;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class IdentityCorrectionHttpIT {
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;private static CryptoService crypto;private static OutboxProcessor processor;private static Capture capture;private static String server,hash;private static Path storage,journal;
    private static final String PASSWORD="Synthetic identity correction password 2026";
    private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();
    static class Capture implements EmailProvider {final List<Message> messages=new CopyOnWriteArrayList<>();public void send(Message input){messages.add(input);}}
    @TestConfiguration static class MailConfig {@Bean @Primary Capture captureIdentityMail(){return new Capture();}}
    private record Session(UUID id,String email,String cookie,String csrf){@Override public String toString(){return "IdentityCorrectionSession[redacted]";}}
    @BeforeAll static void start()throws Exception {
        db=new RealPostgres();db.resetSchema();storage=Files.createTempDirectory("unicarona-identity-storage-");journal=Files.createTempDirectory("unicarona-identity-journal-");var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));args.add("--STORAGE_PATH="+storage);args.add("--PRIVACY_ERASURE_JOURNAL_PATH="+journal);
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,MailConfig.class).run(args.toArray(String[]::new));sql=app.getBean(JdbcTemplate.class);crypto=app.getBean(CryptoService.class);hash=crypto.hashPassword(PASSWORD);capture=app.getBean(Capture.class);processor=app.getBean(OutboxProcessor.class);server="http://127.0.0.1:"+app.getWebServer().getPort();LegalTestFixtures.publish(app);
    }
    @BeforeEach void reset(){sql.update("DELETE FROM rate_limits");sql.update("DELETE FROM outbox_jobs");capture.messages.clear();}
    @AfterAll static void close()throws Exception {if(app!=null)app.close();if(db!=null)db.close();for(var root:List.of(storage,journal)){assertTrue(root.getFileName().toString().startsWith("unicarona-identity-"));try(var files=Files.walk(root)){for(var p:files.sorted(Comparator.reverseOrder()).toList()){assertTrue(p.normalize().startsWith(root.normalize()));Files.delete(p);}}}}
    private String key(){return UUID.randomUUID().toString();}
    private String target(){return "new-"+UUID.randomUUID()+"@example.test";}
    private HttpResponse<String> send(String method,String path,Object body,Session user,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");if(user!=null)request.header("Cookie",user.cookie()).header("x-csrf-token",user.csrf());if(key!=null)request.header("idempotency-key",key);request.method(method,"GET".equals(method)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> result)throws Exception{return json.readTree(result.body());}
    private JsonNode ok(HttpResponse<String> result)throws Exception{assertEquals(200,result.statusCode(),result.body());return tree(result);}
    private Session user(boolean admin)throws Exception {String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at,role) VALUES(?,?,'Synthetic identity user','2000-01-01',now(),?) RETURNING id",UUID.class,email,hash,admin?"admin":"user");return login(id,email,PASSWORD);}
    private Session login(UUID id,String email,String password)throws Exception {var result=send("POST","/api/v1/auth/login",Map.of("email",email,"password",password),null,null);ok(result);var cookies=new LinkedHashMap<String,String>();for(String header:result.headers().allValues("set-cookie")){var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}return new Session(id,email,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));}
    private void request(Session user,String email)throws Exception {var result=send("POST","/api/v1/privacy/identity/email",Map.of("password",PASSWORD,"email",email),user,key());assertEquals(202,result.statusCode(),result.body());}
    private String token(Session user)throws Exception {var job=sql.queryForMap("SELECT o.payload::text,o.event_key FROM auth_tokens t JOIN outbox_jobs o ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind='change_email' AND t.used_at IS NULL AND t.invalidated_at IS NULL",user.id());String url=app.getBean(PrivacyEvidenceCrypto.class).openMail(PrivacyEvidenceCrypto.Field.MAIL_URL,user.id(),(String)job.get("event_key"),json.readTree((String)job.get("payload")).path("urlCiphertext").asText());return url.substring(url.indexOf("?token=")+7);}
    private void drain(){for(int n=0;n<500;n++)if(!processor.processOne())return;fail("Synthetic outbox did not drain");}
    private String email(Session user){return sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,user.id());}
    private UUID pending(Session user){return sql.queryForObject("SELECT id FROM privacy_email_changes WHERE user_id=? AND status='pending'",UUID.class,user.id());}
    @Test void requestRequiresPasswordCsrfAndOwnAccountAndNeverChangesLoginBeforeConfirmation()throws Exception {
        var a=user(false);var b=user(false);String next=target();var body=new LinkedHashMap<String,Object>(Map.of("password",PASSWORD,"email",next));
        assertEquals(401,send("POST","/api/v1/privacy/identity/email",Map.of("password","wrong","email",next),a,key()).statusCode());assertEquals(403,send("POST","/api/v1/privacy/identity/email",body,new Session(a.id(),a.email(),a.cookie(),"wrong"),key()).statusCode());body.put("userId",b.id().toString());assertEquals(422,send("POST","/api/v1/privacy/identity/email",body,a,key()).statusCode());
        request(a,next);assertEquals(a.email(),email(a));assertEquals(b.email(),email(b));String token=token(a);assertEquals(43,token.length());
        String payload=sql.queryForObject("SELECT o.payload::text FROM outbox_jobs o JOIN auth_tokens t ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind='change_email'",String.class,a.id());assertFalse(payload.contains(next));assertFalse(payload.contains(token));assertFalse(sql.queryForObject("SELECT new_email_ciphertext FROM privacy_email_changes WHERE id=?",String.class,pending(a)).contains(next));
        var state=ok(send("GET","/api/v1/privacy/identity",null,a,null));assertFalse(state.toString().contains(next));assertTrue(state.path("pendingEmail").path("maskedEmail").asText().contains("***"));
        drain();assertEquals(1,capture.messages.stream().filter(m->m.kind().equals("change_email")&&m.to().equals(next)).count());assertEquals(1,capture.messages.stream().filter(m->m.kind().equals("email_change_notice")&&m.to().equals(a.email())).count());
    }
    @Test void confirmationNeedsTheOwnersSessionAndPasswordRevokesSessionsAndSendsBothSecurityNotices()throws Exception {
        var a=user(false);var b=user(false);String next=target();request(a,next);String token=token(a);var body=Map.of("password",PASSWORD,"token",token);String key=key();
        assertEquals(400,send("POST","/api/v1/privacy/identity/email/confirm",body,b,key()).statusCode());assertEquals(401,send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password","wrong","token",token),a,key()).statusCode());
        var confirmed=send("POST","/api/v1/privacy/identity/email/confirm",body,a,key);ok(confirmed);assertEquals(next,email(a));assertTrue(sql.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id=?",Boolean.class,a.id()));assertEquals(401,send("GET","/api/v1/auth/me",null,a,null).statusCode());assertEquals(401,send("POST","/api/v1/auth/login",Map.of("email",a.email(),"password",PASSWORD),null,null).statusCode());
        var fresh=login(a.id(),next,PASSWORD);assertEquals(confirmed.body(),send("POST","/api/v1/privacy/identity/email/confirm",body,fresh,key).body());assertNull(sql.queryForObject("SELECT new_email_ciphertext FROM privacy_email_changes WHERE user_id=?",String.class,a.id()));
        drain();assertEquals(2,capture.messages.stream().filter(m->m.kind().equals("email_changed_notice")&&Set.of(a.email(),next).contains(m.to())).count());assertEquals(0,capture.messages.stream().filter(m->m.kind().equals("change_email")&&m.to().equals(next)).count());assertFalse(sql.queryForObject("SELECT metadata::text FROM audit_logs WHERE actor_id=? AND action='email_changed'",String.class,a.id()).contains(next));
    }
    @Test void aNewRequestAndCancellationInvalidateEarlierLinksAndSkipTheirLateDelivery()throws Exception {
        var a=user(false);String first=target(),second=target();request(a,first);String old=token(a);request(a,second);String current=token(a);assertNotEquals(old,current);
        assertEquals(400,send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password",PASSWORD,"token",old),a,key()).statusCode());ok(send("POST","/api/v1/privacy/identity/email/cancel",Map.of("password",PASSWORD),a,key()));assertEquals(400,send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password",PASSWORD,"token",current),a,key()).statusCode());
        drain();assertFalse(capture.messages.stream().anyMatch(m->m.kind().equals("change_email")));assertEquals(a.email(),email(a));assertTrue(ok(send("GET","/api/v1/privacy/identity",null,a,null)).path("pendingEmail").isNull());
    }
    @Test void occupiedAndRestrictedTargetsGetTheSameGenericResponseWithoutAConfirmationEmail()throws Exception {
        var a=user(false);var occupied=user(false);String blocked=target();sql.update("INSERT INTO privacy_registration_restrictions(email_hmac,reason_code,created_by,source_user_id,expires_at) VALUES(?,'security',?,?,now()+interval '1 day')",app.getBean(RegistrationRestrictions.class).fingerprint(blocked),occupied.id(),occupied.id());
        var one=send("POST","/api/v1/privacy/identity/email",Map.of("password",PASSWORD,"email",occupied.email()),a,key());var stateOne=ok(send("GET","/api/v1/privacy/identity",null,a,null));var two=send("POST","/api/v1/privacy/identity/email",Map.of("password",PASSWORD,"email",blocked),a,key());assertEquals(202,one.statusCode());assertEquals(one.body(),two.body());assertFalse(stateOne.toString().contains("eligible"));
        drain();assertFalse(capture.messages.stream().anyMatch(m->m.kind().equals("change_email")));assertEquals(a.email(),email(a));
    }
    @Test void expiryClearsTheTargetUnlessSpecificallyHeldAndReleasedHoldThenAllowsCleaning()throws Exception {
        var a=user(false);var held=user(false);var administrator=user(true);request(a,target());request(held,target());UUID id=pending(held);String retained=sql.queryForObject("SELECT new_email_ciphertext FROM privacy_email_changes WHERE id=?",String.class,id);
        sql.update("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'identity_change','identity_change',?,'regular_exercise_of_rights',?,?,now()+interval '1 hour',now()+interval '1 day')",held.id(),id,crypto.encrypt("Synthetic identity dispute",AuthTestSettings.KEY),administrator.id());
        sql.update("UPDATE privacy_email_changes SET created_at=now()-interval '1 hour',expires_at=now()-interval '1 minute' WHERE user_id IN (?,?)",a.id(),held.id());app.getBean(PrivacyRetentionTransactions.class).prune(100);
        assertNull(sql.queryForObject("SELECT new_email_ciphertext FROM privacy_email_changes WHERE user_id=?",String.class,a.id()));assertEquals(retained,sql.queryForObject("SELECT new_email_ciphertext FROM privacy_email_changes WHERE id=?",String.class,id));assertNull(sql.queryForObject("SELECT credential_hmac FROM privacy_email_changes WHERE id=?",String.class,id));assertTrue(ok(send("GET","/api/v1/privacy/identity",null,held,null)).path("pendingEmail").isNull());
        sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE user_id=?",held.id());app.getBean(PrivacyRetentionTransactions.class).prune(100);assertNull(sql.queryForObject("SELECT new_email_ciphertext FROM privacy_email_changes WHERE id=?",String.class,id));drain();assertFalse(capture.messages.stream().anyMatch(m->m.kind().equals("change_email")));
    }
    @Test void correctionsAreBlockedFromChangingAnEmailDuringSuspensionButAssistedRightsRemainAvailable()throws Exception {
        var a=user(false);request(a,target());String token=token(a);sql.update("UPDATE users SET account_status='suspended' WHERE id=?",a.id());assertEquals(409,send("POST","/api/v1/privacy/identity/email",Map.of("password",PASSWORD,"email",target()),a,key()).statusCode());assertEquals(409,send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password",PASSWORD,"token",token),a,key()).statusCode());assertEquals(202,send("POST","/api/v1/privacy/requests",Map.of("kind","correction","details","Synthetic request for assisted identity correction."),a,key()).statusCode());
        drain();assertFalse(capture.messages.stream().anyMatch(m->m.kind().equals("change_email")));assertEquals(a.email(),email(a));
    }
    @Test void adultBirthCorrectionIsReauthenticatedDoesNotExposeDatesInAuditAndDoesNotRestoreRestrictions()throws Exception {
        var a=user(false);assertEquals(422,send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth","2000-02-30"),a,key()).statusCode());assertEquals(422,send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth","2099-01-01"),a,key()).statusCode());assertEquals(401,send("POST","/api/v1/privacy/identity/birth",Map.of("password","wrong","birth","1999-01-02"),a,key()).statusCode());
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",a.id());ok(send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth","1999-01-02"),a,key()));assertEquals("1999-01-02",sql.queryForObject("SELECT birth_date::text FROM users WHERE id=?",String.class,a.id()));assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));assertFalse(sql.queryForObject("SELECT metadata::text FROM audit_logs WHERE actor_id=? AND action='birth_date_corrected'",String.class,a.id()).contains("1999-01-02"));
    }
    @Test void aCorrectionRevealingAMinorKeepsAccurateDataAndRestrictsNewServiceUntilAnActualReview()throws Exception {
        var a=user(false);var administrator=user(true);String minor=LocalDate.now(ZoneId.of("America/Sao_Paulo")).minusYears(17).toString();var corrected=ok(send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth",minor),a,"a".repeat(128)));assertEquals("restricted",corrected.path("status").asText());assertEquals(minor,sql.queryForObject("SELECT birth_date::text FROM users WHERE id=?",String.class,a.id()));assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));assertEquals(403,send("GET","/api/v1/rides",null,a,null).statusCode());
        var review=Map.of("status","active","expectedStatus","suspended","reason","Synthetic eligibility review of the corrected identity.");assertEquals(409,send("POST","/api/v1/admin/accounts/"+a.id()+"/review",review,administrator,key()).statusCode());
        ok(send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth","2000-01-01"),a,key()));assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));ok(send("POST","/api/v1/admin/accounts/"+a.id()+"/review",review,administrator,key()));
    }
    @Test void correctingAgeDuringClosureCannotUseCancellationToRestoreAnUnderageAccount()throws Exception {
        var a=user(false);String version=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();assertEquals(202,send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),a,key()).statusCode());
        ok(send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth",LocalDate.now().minusYears(17).toString()),a,key()));ok(send("POST","/api/v1/privacy/closure/cancel",Map.of("password",PASSWORD),a,key()));assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));
    }
    @Test void profileErasureRemovesPendingTargetsAndPrivateJobsAndStopsDelivery()throws Exception {
        var a=user(false);request(a,target());String version=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();assertEquals(202,send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),a,key()).statusCode());sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE user_id=?",a.id());app.getBean(PrivacyErasureMaintenance.class).run(100,()->false);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_email_changes WHERE user_id=?",Integer.class,a.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE payload->>'ownerId'=?",Integer.class,a.id().toString()));drain();assertFalse(capture.messages.stream().anyMatch(m->m.to().equals(a.email())));assertEquals(401,send("GET","/api/v1/privacy/identity",null,a,null).statusCode());
    }
    @Test void changingThePasswordMakesAnEarlierEmailLinkUnusableAndSkipsItsDelivery()throws Exception {
        var a=user(false);String next=target(),replacement="Synthetic replacement password 2026";request(a,next);String token=token(a);
        ok(send("POST","/api/v1/auth/change-password",Map.of("currentPassword",PASSWORD,"password",replacement),a,null));var fresh=login(a.id(),a.email(),replacement);
        assertEquals(400,send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password",replacement,"token",token),fresh,key()).statusCode());assertTrue(ok(send("GET","/api/v1/privacy/identity",null,fresh,null)).path("pendingEmail").isNull());drain();assertFalse(capture.messages.stream().anyMatch(m->m.kind().equals("change_email")));assertEquals(a.email(),email(a));
    }
    @Test void verifyingANewEmailDoesNotReactivateAnAccountOrChangeMarketing()throws Exception {
        var a=user(false);ok(send("POST","/api/v1/privacy/account/deactivate",Map.of("password",PASSWORD,"acknowledged",true),a,key()));String next=target();request(a,next);String token=token(a);ok(send("POST","/api/v1/privacy/identity/email/confirm",Map.of("password",PASSWORD,"token",token),a,key()));var fresh=login(a.id(),next,PASSWORD);
        assertEquals("deactivated",ok(send("GET","/api/v1/privacy/account",null,fresh,null)).path("status").asText());assertFalse(ok(send("GET","/api/v1/privacy/preferences",null,fresh,null)).path("marketing").asBoolean());assertEquals(403,send("GET","/api/v1/rides",null,fresh,null).statusCode());
    }
    @Test void aSpecificIdentityHoldRetainsOnlyNecessaryEncryptedEvidenceWhenTheProfileIsErased()throws Exception {
        var a=user(false);var administrator=user(true);String next=target();request(a,next);UUID change=pending(a);
        sql.update("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'identity_change','identity_change',?,'regular_exercise_of_rights',?,?,now()+interval '1 hour',now()+interval '1 day')",a.id(),change,crypto.encrypt("Synthetic specific identity dispute",AuthTestSettings.KEY),administrator.id());
        String version=ok(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();assertEquals(202,send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),a,key()).statusCode());sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE user_id=?",a.id());app.getBean(PrivacyErasureMaintenance.class).run(100,()->false);
        assertNotNull(sql.queryForObject("SELECT deleted_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_email_changes WHERE user_id=?",Integer.class,a.id()));
        var record=sql.queryForMap("SELECT id,content_ciphertext FROM privacy_retained_records WHERE user_id=? AND category='identity_change'",a.id());String protectedEvidence=(String)record.get("content_ciphertext");assertFalse(protectedEvidence.contains(next));var evidence=json.readTree(app.getBean(PrivacyEvidenceCrypto.class).open(PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,a.id(),(UUID)record.get("id"),protectedEvidence));assertFalse(evidence.has("credential_hmac"));assertFalse(evidence.has("token_id"));assertFalse(evidence.has("new_email_ciphertext"));assertEquals(next,evidence.path("new_email").asText());
    }
}
