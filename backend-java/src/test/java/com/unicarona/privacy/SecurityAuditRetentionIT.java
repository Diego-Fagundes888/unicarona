package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.dto.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.*;
import com.unicarona.support.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic PostgreSQL, authenticated preservation and actual pg_dump/pg_restore. */
class SecurityAuditRetentionIT {
    @TempDir Path temporary;private static RealPostgres database;private ServletWebServerApplicationContext app;private JdbcTemplate sql;private String[] args;
    private static final String PASSWORD="Synthetic security audit test password 2026";
    @BeforeAll static void database(){database=new RealPostgres();}
    @AfterAll static void databaseClose(){if(database!=null)database.close();}
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    @BeforeEach void setup()throws Exception {
        database.resetSchema();var values=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));values.addAll(List.of("--STORAGE_PATH="+temporary.resolve("files"),"--FRONTEND_PATH="+temporary.resolve("public"),"--PRIVACY_OPERATIONS_ROOT="+temporary.resolve("ops"),"--PRIVACY_ERASURE_JOURNAL_PATH="+temporary.resolve("journal"),"--DEV_MAIL_PATH="+temporary.resolve("mail")));args=values.toArray(String[]::new);
        var env=new MockEnvironment();for(String arg:args){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,Clock.systemUTC()).initialize();start();
    }
    private void start(){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args);sql=app.getBean(JdbcTemplate.class);}
    private Actor owner(){String hash=app.getBean(CryptoService.class).hashPassword(PASSWORD);UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic security audit owner','2000-01-01',now()) RETURNING id",UUID.class,UUID.randomUUID()+"@example.test",hash);return new Actor(id,"user","active",null,null,null,true,Set.of());}
    private Actor admin(){var value=owner();sql.update("UPDATE users SET role='admin' WHERE id=?",value.id());var tokens=app.getBean(com.unicarona.auth.service.AuthService.class).login(sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,value.id()),PASSWORD).tokens();return new Actor(value.id(),"admin","active",tokens.sessionId(),null,null,true,Set.of("ROLE_ADMIN"));}
    private UUID event(Actor owner,String action,boolean expired){return sql.queryForObject("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata,created_at) VALUES(?,?,'user',?,'{\"syntheticMarker\":\"Synthetic security evidence only\"}',now()-(?*interval '1 month')) RETURNING id",UUID.class,owner==null?null:owner.id(),action,owner==null?null:owner.id(),expired?7:0);}
    private UUID endingSoon(Actor owner)throws Exception {
        return sql.queryForObject("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata,created_at) VALUES(?,'password_changed','user',?,'{\"syntheticMarker\":\"Synthetic security evidence only\"}',((clock_timestamp() AT TIME ZONE 'America/Sao_Paulo')-interval '6 months'+interval '5 seconds') AT TIME ZONE 'America/Sao_Paulo') RETURNING id",UUID.class,owner.id(),owner.id());
    }
    private void awaitExpiry(UUID id)throws Exception {long millis=sql.queryForObject("SELECT greatest(0,ceil(extract(epoch FROM privacy_security_audit_deadline(a)-clock_timestamp())*1000))::bigint FROM audit_logs a WHERE id=?",Long.class,id);assertTrue(millis<15000);Thread.sleep(millis+100);}
    private PrivacySecurityAuditRetention tx(){return app.getBean(PrivacySecurityAuditRetention.class);}
    private boolean retire(Actor owner,UUID id)throws Exception{return tx().retire(new PrivacySecurityAuditRetention.Candidate(id,owner==null?null:owner.id()),true);}
    private boolean exists(UUID id){return sql.queryForObject("SELECT EXISTS(SELECT 1 FROM audit_logs WHERE id=?)",Boolean.class,id);}
    private PrivacyHoldDto.Item hold(Actor admin,Actor owner,UUID id){return app.getBean(AdminPrivacyHoldService.class).create(admin,new PrivacyHoldDto.Create(PASSWORD,owner.id(),"security_audit",id,"regular_exercise_of_rights","Synthetic specific security investigation requiring this event","Synthetic security case",Instant.now().plusSeconds(3600),Instant.now().plusSeconds(86400)),UUID.randomUUID().toString());}
    private String held(Actor admin,UUID id){return new String(app.getBean(AdminPrivacyHoldService.class).heldData(admin,id,new PrivacyDto.Reauthenticate(PASSWORD)).data(),java.nio.charset.StandardCharsets.UTF_8);}
    private JsonNode exported(Actor owner)throws Exception {var bytes=new java.io.ByteArrayOutputStream();app.getBean(PrivacyExportService.class).write(owner.id(),bytes);try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals("dados.json"))return app.getBean(ObjectMapper.class).readTree(zip.readAllBytes());}throw new AssertionError("Synthetic export absent");}
    @Test void updateAndPrematureDeletionStayForbiddenAndOnlyExpiredClassifiedEventsAreRemoved()throws Exception {
        var owner=owner();UUID current=event(owner,"password_changed",false),old=event(owner,"password_reset",true),financial=event(owner,"payment_event_processed",true);
        for(UUID id:List.of(current,old,financial))assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE audit_logs SET created_at=created_at-interval '1 year' WHERE id=?",id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM audit_logs WHERE id=?",current));assertFalse(retire(owner,current));assertFalse(retire(owner,financial));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM audit_logs WHERE id=?",financial));assertTrue(retire(owner,old));assertFalse(exists(old));assertTrue(exists(current));assertTrue(exists(financial));
    }
    @Test void sixCalendarMonthsUseOriginalEventAndFixedBrazilTimezone()throws Exception {
        var owner=owner();UUID id=sql.queryForObject("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) VALUES(?,'password_changed','user',?,'2025-08-31 12:00:00-03') RETURNING id",UUID.class,owner.id(),owner.id());
        assertEquals(Instant.parse("2026-02-28T15:00:00Z"),sql.queryForObject("SELECT privacy_security_audit_deadline(a) FROM audit_logs a WHERE id=?",Timestamp.class,id).toInstant());
        assertNull(sql.queryForObject("SELECT privacy_security_audit_deadline(a) FROM audit_logs a WHERE id=?",Timestamp.class,event(owner,"account_registered",true)));
    }
    @Test void expiredEventsDisappearFromOwnExportBeforeMaintenanceWithoutHidingCurrentOrOtherPurposes()throws Exception {
        var owner=owner();UUID old=event(owner,"password_reset",true),current=event(owner,"settings_updated",false);event(owner,"account_registered",true);String events=exported(owner).path("accountEvents").toString();assertFalse(events.contains("password_reset"));assertFalse(events.contains(old.toString()));assertTrue(events.contains(current.toString()));assertTrue(events.contains("settings_updated"));assertTrue(events.contains("account_registered"));assertTrue(exists(old));
    }
    @Test void anUnidentifiedInvalidVerificationEventAlsoExpiresWithoutInventingAnOwner()throws Exception {
        UUID id=event(null,"email_verification_invalid",true);assertTrue(retire(null,id));assertFalse(exists(id));
    }
    @Test void anExpiredEventCannotBeNewlyPreservedOrAccessedAsAnotherPersonsEvidence()throws Exception {
        var owner=owner();var other=owner();var admin=admin();UUID old=event(owner,"password_changed",true),current=event(owner,"settings_updated",false);assertEquals("PRIVACY_RESOURCE_UNAVAILABLE",assertThrows(com.unicarona.shared.ApiException.class,()->hold(admin,owner,old)).code());assertEquals("PRIVACY_RESOURCE_UNAVAILABLE",assertThrows(com.unicarona.shared.ApiException.class,()->hold(admin,other,current)).code());assertTrue(exists(old));
    }
    @Test void privateCaseCopySurvivesOrdinaryExpiryAndReleaseEndsItsAccess()throws Exception {
        var owner=owner();var admin=admin();UUID id=endingSoon(owner);var hold=hold(admin,owner,id);assertFalse(sql.queryForObject("SELECT content_ciphertext FROM privacy_retained_records WHERE category='security_audit'",String.class).contains("Synthetic security evidence only"));awaitExpiry(id);assertTrue(retire(owner,id));assertFalse(exists(id));assertTrue(held(admin,hold.id()).contains("Synthetic security evidence only"));assertFalse(exported(owner).path("accountEvents").toString().contains("password_changed"));
        var content=exported(owner).path("retainedSecurityEvents").get(0).path("content");assertEquals(id.toString(),content.path("id").asText());assertEquals("password_changed",content.path("action").asText());assertFalse(content.has("actor_id"));assertFalse(content.has("metadata_json"));assertFalse(content.has("request_id"));
        app.getBean(AdminPrivacyHoldService.class).review(admin,hold.id(),new PrivacyHoldDto.Review(PASSWORD,1,"release","Synthetic investigation concluded without further retention","Synthetic security case",null,null),UUID.randomUUID().toString());assertEquals("PRIVACY_HOLD_INACTIVE",assertThrows(com.unicarona.shared.ApiException.class,()->held(admin,hold.id())).code());assertEquals(0,exported(owner).path("retainedSecurityEvents").size());app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE category='security_audit'",Integer.class));
    }
    @Test void caseWithoutPrivateCopyCannotAuthorizeDeletion()throws Exception {
        var owner=owner();var admin=admin();UUID id=endingSoon(owner);hold(admin,owner,id);var journal=app.getBean(PrivacyHoldControlJournal.class);long sequence=journal.current().orElseThrow().head().sequence();sql.update("DELETE FROM privacy_retained_records WHERE category='security_audit'");awaitExpiry(id);assertFalse(retire(owner,id));assertTrue(exists(id));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM audit_logs WHERE id=?",id));assertThrows(RuntimeException.class,()->app.getBean(PrivacyHoldRecovery.class).beforeReadiness());assertEquals(sequence,journal.current().orElseThrow().head().sequence());assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void anUnconfirmedIndependentCheckpointStopsDeletion()throws Exception {
        var owner=owner();UUID id=event(owner,"password_reset",true);app.getBean(PrivacyHoldControlJournal.class).prepare(app.getBean(ObjectMapper.class).createObjectNode().put("syntheticUnconfirmedPreparation",true));assertThrows(java.io.IOException.class,()->retire(owner,id));assertTrue(exists(id));assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void anUnauthenticatablePrivateCopyDoesNotAuthorizeDeletion()throws Exception {
        var owner=owner();var admin=admin();UUID id=endingSoon(owner);hold(admin,owner,id);String cipher=sql.queryForObject("SELECT content_ciphertext FROM privacy_retained_records WHERE category='security_audit'",String.class);int at=cipher.lastIndexOf('.')+5;String bad=cipher.substring(0,at)+(cipher.charAt(at)=='A'?'B':'A')+cipher.substring(at+1);sql.update("UPDATE privacy_retained_records SET content_ciphertext=? WHERE category='security_audit'",bad);awaitExpiry(id);assertThrows(java.io.IOException.class,()->retire(owner,id));assertTrue(exists(id));assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void aLockedOwnerDoesNotStarveAnotherExpiredEvent()throws Exception {
        var a=owner();var b=owner();event(a,"password_changed",true);event(b,"password_reset",true);var first=tx().candidates(null,1).getFirst();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var writer=executor.submit(()->app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",first.owner());locked.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException stop){throw new IllegalStateException(stop);}}));try{assertTrue(locked.await(8,TimeUnit.SECONDS));var task=app.getBean(PrivacySecurityAuditMaintenance.class);assertEquals(0,task.runOnce(1,()->false));assertEquals(1,task.runOnce(1,()->false));assertTrue(exists(first.id()));}finally{release.countDown();}writer.get(8,TimeUnit.SECONDS);}finally{release.countDown();}
    }
    @Test void startupReappliesOriginalExpiryFromAnActualArchiveBeforeReadiness()throws Exception {
        var owner=owner();UUID id=event(owner,"password_changed",true);Path archive=temporary.resolve("security-expired.dump");database.dumpDatabaseArchive(archive);assertTrue(retire(owner,id));app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(archive);start();assertFalse(exists(id));assertTrue(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void postBackupSpecificCaseRecoversWithoutRecreatingItsMissingOrdinaryEvent()throws Exception {
        var owner=owner();var admin=admin();Path archive=temporary.resolve("before-security-case.dump");database.dumpDatabaseArchive(archive);UUID id=endingSoon(owner);var hold=hold(admin,owner,id);awaitExpiry(id);assertTrue(retire(owner,id));app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(archive);start();assertTrue(app.getBean(PrivacyReadiness.class).ready());assertFalse(exists(id));assertTrue(held(admin,hold.id()).contains(id.toString()));assertTrue(held(admin,hold.id()).contains("Synthetic security evidence only"));
    }
    @Test void boundedBatchesRespectStopAndFinishALargerSyntheticSetBeforeReadiness()throws Exception {
        var owner=owner();sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) SELECT ?,'password_changed','user',?,now()-interval '7 months' FROM generate_series(1,1201)",owner.id(),owner.id());var task=app.getBean(PrivacySecurityAuditMaintenance.class);
        assertEquals(100,task.runOnce(100,()->false));assertEquals(1101,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='password_changed'",Integer.class));assertEquals(0,task.runOnce(100,()->true));assertEquals(100,task.runOnce(100,()->false));task.beforeReadiness();assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='password_changed'",Integer.class));assertEquals(1201,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='security_audit_expired' AND actor_id IS NULL AND resource_id IS NULL",Integer.class));
    }
    @Test void anOverflowingNumericFormatCannotBeReinterpretedAsALegacyPreservationSnapshot()throws Exception {
        var state=app.getBean(ObjectMapper.class).createObjectNode().put("format",4294967297L).put("kind","privacy-retention-snapshot");for(String name:List.of("holds","events","retained","files"))state.putArray(name);
        assertThrows(java.io.IOException.class,()->app.getBean(PrivacyRetentionSnapshot.class).replay(state));assertTrue(app.getBean(PrivacyReadiness.class).ready());
    }
}
