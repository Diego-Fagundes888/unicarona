package com.unicarona.auth;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.service.AuthService;
import com.unicarona.job.service.OutboxProcessor;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/Security/PostgreSQL/outbox; only the external delivery transport is a test double. */
class EmailVerificationHttpIT {
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;
    private static String origin;private static UUID university,course;private static CapturingEmail mail;private static OutboxProcessor processor;private static CryptoService crypto;
    private static final String PASSWORD="Synthetic verification password 2026";
    private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();
    static class CapturingEmail implements EmailProvider {
        final List<Message> messages=new CopyOnWriteArrayList<>();volatile boolean fail;
        public void send(Message message){if(fail)throw new IllegalStateException("synthetic-private-provider-error");messages.add(message);}
    }
    @TestConfiguration static class DeliveryConfig {@Bean @Primary CapturingEmail capture(){return new CapturingEmail();}}
    @BeforeAll static void start() throws Exception {
        db=new RealPostgres();db.resetSchema();app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,DeliveryConfig.class).run(AuthTestSettings.arguments(db.apiConnectionUrl()));
        sql=app.getBean(JdbcTemplate.class);crypto=app.getBean(CryptoService.class);mail=app.getBean(CapturingEmail.class);processor=app.getBean(OutboxProcessor.class);origin="http://127.0.0.1:"+app.getWebServer().getPort();
        LegalTestFixtures.publish(app);
        university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('email-proof','Email proof','Gama','DF') RETURNING id",UUID.class);
        course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'email-proof','Email proof') RETURNING id",UUID.class,university);
    }
    @BeforeEach void reset(){sql.update("DELETE FROM rate_limits");sql.update("DELETE FROM outbox_jobs");mail.fail=false;mail.messages.clear();}
    @AfterAll static void close(){if(app!=null)app.close();if(db!=null)db.close();}
    private HttpResponse<String> post(String path,Object body)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/auth/"+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> serviceStatus(String query)throws Exception {return http.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/auth/email-service-status"+query)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private record Account(UUID id,String email,String token){}
    private Account create()throws Exception{return create("verify-"+UUID.randomUUID()+"@example.test");}
    private Account create(String email)throws Exception {
        var input=Map.of("name","Ana Teste","email",email,"password",PASSWORD,"birth","2000-01-01","truth",true,"terms",true,
            "academic",Map.of("universityId",university.toString(),"courseId",course.toString(),"region","Gama","periodStatus","numbered","periodNumber",1,"shift","night","classLabel","A"),"legal",LegalTestFixtures.registration(app));
        var result=post("register",input);assertEquals(202,result.statusCode(),result.body());assertEquals(60,json.readTree(result.body()).path("retryAfterSeconds").asInt());String normalized=email.strip().toLowerCase(Locale.ROOT);
        UUID id=sql.queryForObject("SELECT id FROM users WHERE email=?",UUID.class,normalized);return new Account(id,normalized,token(id));
    }
    private String token(UUID id)throws Exception {
        String url=EncryptedMailFixture.url(app,sql,id,"verify_email");return url.substring(url.indexOf("?token=")+7);
    }
    private void code(HttpResponse<String> response,int status,String code)throws Exception {assertEquals(status,response.statusCode(),response.body());assertEquals(code,json.readTree(response.body()).path("code").asText());}
    private boolean verified(Account a){return sql.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id=?",Boolean.class,a.id());}
    private void allowResend(){sql.update("DELETE FROM rate_limits");}
    @Test void registrationNormalizesHashesAndActuallyDispatchesTheFrozenTemplate()throws Exception {
        var a=create("  Verify-"+UUID.randomUUID()+"@EXAMPLE.TEST  ");assertFalse(verified(a));assertEquals(43,a.token().length());
        assertEquals(60,sql.queryForObject("SELECT extract(epoch FROM expires_at-created_at)::int/60 FROM auth_tokens WHERE user_id=?",Integer.class,a.id()));
        assertEquals(crypto.hmac(a.token(),AuthTestSettings.PEPPER),sql.queryForObject("SELECT token_hash FROM auth_tokens WHERE user_id=?",String.class,a.id()));
        String encryptedJob=sql.queryForObject("SELECT payload::text FROM outbox_jobs WHERE id=?",String.class,EncryptedMailFixture.job(sql,a.id()));assertFalse(encryptedJob.contains(a.token()));assertFalse(encryptedJob.contains(a.email()));assertFalse(json.readTree(encryptedJob).has("to"));
        assertTrue(processor.processOne());assertEquals(1,mail.messages.size());assertNotNull(mail.messages.getFirst().content());assertTrue(mail.messages.getFirst().content().html().contains("Verificar meu e-mail"));
        assertTrue(sql.queryForObject("SELECT sent_at IS NOT NULL FROM auth_tokens WHERE user_id=?",Boolean.class,a.id()));
        assertEquals("{}",sql.queryForObject("SELECT o.payload::text FROM outbox_jobs o JOIN auth_tokens t ON o.event_key='auth:'||t.id::text WHERE t.user_id=? AND t.kind='verify_email'",String.class,a.id()));
        assertEquals(200,post("verify-email",Map.of("token",a.token())).statusCode());assertTrue(verified(a));assertEquals(200,post("login",Map.of("email",a.email(),"password",PASSWORD)).statusCode());
    }
    @Test void unverifiedLoginHasNoSessionAndCannotReachProtectedFunctions()throws Exception {
        var a=create();var r=post("login",Map.of("email",a.email(),"password",PASSWORD));code(r,403,"EMAIL_UNVERIFIED");assertTrue(r.headers().allValues("set-cookie").isEmpty());assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void expiredLinkIsReportedAndDoesNotVerifyOrSendExpiredMail()throws Exception {
        var a=create();sql.update("UPDATE auth_tokens SET expires_at=now()-interval '1 second' WHERE user_id=?",a.id());code(post("verify-email",Map.of("token",a.token())),400,"TOKEN_EXPIRED");assertFalse(verified(a));assertTrue(processor.processOne());assertEquals(0,mail.messages.size());assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='email_verification_expired'",Integer.class,a.id()));
    }
    @Test void invalidOrResetTokenCannotVerifyAndExtraUserIdentityIsRejected()throws Exception {
        var a=create();var b=create();code(post("verify-email",Map.of("token",a.token(),"userId",b.id())),422,"VALIDATION_ERROR");
        code(post("verify-email",Map.of("token","a".repeat(43))),400,"TOKEN_INVALID");assertFalse(verified(a));assertFalse(verified(b));
        assertEquals(200,post("forgot-password",Map.of("email",a.email())).statusCode());String hash=sql.queryForObject("SELECT token_hash FROM auth_tokens WHERE user_id=? AND kind='reset_password'",String.class,a.id());assertNotEquals(crypto.hmac(a.token(),AuthTestSettings.PEPPER),hash);
        String resetUrl=EncryptedMailFixture.url(app,sql,a.id(),"reset_password");String resetToken=resetUrl.substring(resetUrl.indexOf("?token=")+7);
        code(post("verify-email",Map.of("token",resetToken)),400,"TOKEN_INVALID");assertFalse(verified(a));
        assertEquals(200,post("verify-email",Map.of("token",a.token())).statusCode());assertFalse(verified(b));
    }
    @Test void usedTokenDoesNotReplayOrChangeVerificationTime()throws Exception {
        var a=create();assertEquals(200,post("verify-email",Map.of("token",a.token())).statusCode());var time=sql.queryForObject("SELECT email_verified_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id());code(post("verify-email",Map.of("token",a.token())),400,"TOKEN_USED");assertEquals(time,sql.queryForObject("SELECT email_verified_at FROM users WHERE id=?",java.sql.Timestamp.class,a.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='email_verified'",Integer.class,a.id()));
    }
    @Test void resendHasCooldownAndInvalidatesPreviousLinkWithoutDispatchingIt()throws Exception {
        var a=create();code(post("resend-verification",Map.of("email",a.email())),429,"EMAIL_COOLDOWN");allowResend();assertEquals(200,post("resend-verification",Map.of("email",a.email())).statusCode());String fresh=token(a.id());assertNotEquals(a.token(),fresh);
        code(post("verify-email",Map.of("token",a.token())),400,"TOKEN_REPLACED");while(processor.processOne()){}assertEquals(1,mail.messages.size());assertTrue(mail.messages.getFirst().url().endsWith(fresh));assertEquals(200,post("verify-email",Map.of("token",fresh)).statusCode());
    }
    @Test void unknownAndAlreadyVerifiedAccountsHaveTheSameGenericResendContract()throws Exception {
        var a=create();post("verify-email",Map.of("token",a.token()));allowResend();var existing=post("resend-verification",Map.of("email",a.email()));var unknown=post("resend-verification",Map.of("email","unknown@example.test"));assertEquals(200,existing.statusCode());assertEquals(existing.body(),unknown.body());assertEquals(1,sql.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id=? AND kind='verify_email'",Integer.class,a.id()));
    }
    @Test void providerFailureIsAuditedAndRetriesPreserveTheSameMessageIdentity()throws Exception {
        var a=create();mail.fail=true;assertTrue(processor.processOne());assertFalse(verified(a));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='email_delivery_failed'",Integer.class,a.id()));
        assertTrue(json.readTree(serviceStatus("").body()).path("delayed").asBoolean());assertFalse(serviceStatus("").body().contains(a.email()));
        assertEquals("Error",sql.queryForObject("SELECT last_error_code FROM outbox_jobs WHERE id=?",String.class,EncryptedMailFixture.job(sql,a.id())));assertFalse(sql.queryForObject("SELECT coalesce(string_agg(metadata::text,''),'') FROM audit_logs WHERE actor_id=?",String.class,a.id()).contains(a.token()));
        UUID job=EncryptedMailFixture.job(sql,a.id());mail.fail=false;sql.update("UPDATE outbox_jobs SET available_at=now() WHERE id=?",job);assertTrue(processor.processOne());assertEquals(job,mail.messages.getFirst().id());assertFalse(json.readTree(serviceStatus("").body()).path("delayed").asBoolean());assertEquals(200,post("verify-email",Map.of("token",a.token())).statusCode());
    }
    @Test void concurrentClicksHaveExactlyOneWinner()throws Exception {
        var a=create();try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {var gate=new CountDownLatch(1);Callable<Integer> click=()->{gate.await();return post("verify-email",Map.of("token",a.token())).statusCode();};var x=executor.submit(click);var y=executor.submit(click);gate.countDown();assertEquals(Set.of(200,400),Set.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS)));}assertTrue(verified(a));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='email_verified'",Integer.class,a.id()));
    }
    @Test void supersededFailureRemainsAuditableWithoutShowingAnOutdatedServiceWarning()throws Exception {
        var a=create();mail.fail=true;assertTrue(processor.processOne());
        UUID old=EncryptedMailFixture.job(sql,a.id());
        sql.update("UPDATE outbox_jobs SET status='dead' WHERE id=?",old);
        assertTrue(json.readTree(serviceStatus("").body()).path("delayed").asBoolean());
        allowResend();assertEquals(200,post("resend-verification",Map.of("email",a.email())).statusCode());
        assertFalse(json.readTree(serviceStatus("").body()).path("delayed").asBoolean());
        mail.fail=false;assertTrue(processor.processOne());assertEquals(1,mail.messages.size());
        assertEquals("dead",sql.queryForObject("SELECT status FROM outbox_jobs WHERE id=?",String.class,old));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='email_delivery_failed'",Integer.class,a.id()));
        assertFalse(json.readTree(serviceStatus("").body()).path("delayed").asBoolean());
    }
    @Test void verificationRacingResendCannotLeaveMultipleValidTokens()throws Exception {
        var a=create();allowResend();try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var x=executor.submit(()->{gate.await();return post("verify-email",Map.of("token",a.token())).statusCode();});var y=executor.submit(()->{gate.await();return post("resend-verification",Map.of("email",a.email())).statusCode();});gate.countDown();assertTrue(Set.of(200,400).contains(x.get(10,TimeUnit.SECONDS)));assertEquals(200,y.get(10,TimeUnit.SECONDS));}assertTrue(sql.queryForObject("SELECT count(*)<=1 FROM auth_tokens WHERE user_id=? AND used_at IS NULL AND invalidated_at IS NULL",Boolean.class,a.id()));
    }
    @Test void resendLimitsAddressAcrossIpsAndIpAcrossAddresses()throws Exception {
        var a=create();var auth=app.getBean(AuthService.class);
        for(int i=0;i<5;i++){sql.update("DELETE FROM rate_limits WHERE bucket=?",crypto.hmac("email-cooldown:"+a.email(),AuthTestSettings.PEPPER));auth.resend(a.email());}
        sql.update("DELETE FROM rate_limits WHERE bucket=?",crypto.hmac("email-cooldown:"+a.email(),AuthTestSettings.PEPPER));var error=assertThrows(com.unicarona.shared.ApiException.class,()->auth.resend(a.email()));assertEquals("RATE_LIMITED",error.code());
        allowResend();for(int i=0;i<20;i++)assertEquals(200,post("resend-verification",Map.of("email","missing"+i+"@example.test")).statusCode());code(post("resend-verification",Map.of("email","last@example.test")),429,"RATE_LIMITED");
    }
    @Test void deletedAccountCannotBeVerified()throws Exception {
        var a=create();sql.update("UPDATE users SET deleted_at=now(),account_status='deleted' WHERE id=?",a.id());code(post("verify-email",Map.of("token",a.token())),400,"TOKEN_INVALID");assertFalse(verified(a));
    }
    @Test void invalidCatalogDoesNotRevealWhetherTheEmailExists()throws Exception {
        var a=create();String unknown="unknown-"+UUID.randomUUID()+"@example.test";
        var invalidAcademic=Map.of("universityId",university.toString(),"courseId",UUID.randomUUID().toString(),"region","Gama","periodStatus","numbered","periodNumber",1,"shift","night");
        var existing=post("register",Map.of("name","Ana Teste","email",a.email(),"password",PASSWORD,"birth","2000-01-01","truth",true,"terms",true,"academic",invalidAcademic,"legal",LegalTestFixtures.registration(app)));
        var missing=post("register",Map.of("name","Ana Teste","email",unknown,"password",PASSWORD,"birth","2000-01-01","truth",true,"terms",true,"academic",invalidAcademic,"legal",LegalTestFixtures.registration(app)));
        code(existing,422,"INVALID_COURSE");code(missing,422,"INVALID_COURSE");assertEquals(json.readTree(existing.body()).path("message"),json.readTree(missing.body()).path("message"));
    }
    @Test void serviceSignalIsAnonymousGeneralAndNeverAcceptsAnAddress()throws Exception {
        var result=serviceStatus("");assertEquals(200,result.statusCode());assertEquals(Set.of("delayed","message"),json.convertValue(json.readTree(result.body()),Map.class).keySet());assertFalse(json.readTree(result.body()).path("delayed").asBoolean());
        code(serviceStatus("?email=unknown@example.test"),422,"VALIDATION_ERROR");
    }
}
