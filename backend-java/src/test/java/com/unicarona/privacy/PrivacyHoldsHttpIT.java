package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyHoldsHttpIT {
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;private static String server,hash;private static Path storage,journal;
    private static final String PASSWORD="Synthetic privacy holds password 2026";
    private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf){}
    private record Gate(String key,CountDownLatch entered,CountDownLatch release){}
    static class BlockingStorage extends PrivateFileStorage {
        final AtomicReference<Gate> gate=new AtomicReference<>();BlockingStorage(StorageSettings settings){super(settings);}
        @Override public void eraseVerified(String key)throws IOException {Gate current=gate.get();if(current!=null&&current.key().equals(key)){current.entered().countDown();try{if(!current.release().await(10,TimeUnit.SECONDS))throw new IOException("Synthetic erase gate timeout.");}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException("Synthetic erase interrupted.");}}super.eraseVerified(key);}
    }
    @TestConfiguration static class StorageConfig {@Bean @Primary BlockingStorage heldStorage(StorageSettings settings){return new BlockingStorage(settings);}}
    @BeforeAll static void start()throws Exception {
        db=new RealPostgres();db.resetSchema();storage=Files.createTempDirectory("unicarona-holds-files-");journal=Files.createTempDirectory("unicarona-holds-journal-");var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));args.add("--STORAGE_PATH="+storage);args.add("--PRIVACY_ERASURE_JOURNAL_PATH="+journal);
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,StorageConfig.class).run(args.toArray(String[]::new));sql=app.getBean(JdbcTemplate.class);hash=app.getBean(CryptoService.class).hashPassword(PASSWORD);server="http://127.0.0.1:"+app.getWebServer().getPort();LegalTestFixtures.publish(app);
    }
    @BeforeEach void reset(){sql.update("DELETE FROM rate_limits");}
    @AfterAll static void close()throws Exception {if(app!=null)app.close();if(db!=null)db.close();for(Path root:List.of(storage,journal)){assertTrue(root.getFileName().toString().startsWith("unicarona-holds-"));try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){assertTrue(p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));Files.delete(p);}}}}
    private String key(){return UUID.randomUUID().toString();}
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");if(session!=null)request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());if(key!=null)request.header("idempotency-key",key);
        request.method(method,"GET".equals(method)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private Session user(boolean admin)throws Exception {String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at,role) VALUES(?,?,'Synthetic held profile','2000-01-01',now(),?) RETURNING id",UUID.class,email,hash,admin?"admin":"user");var result=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,result.statusCode());var cookies=new LinkedHashMap<String,String>();for(String header:result.headers().allValues("set-cookie")){String[] pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}return new Session(id,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));}
    private JsonNode tree(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    private Map<String,Object> createBody(Session owner,String category,UUID resource){return new LinkedHashMap<>(Map.of("password",PASSWORD,"userId",owner.id(),"category",category,"resourceId",resource,"legalBasis","regular_exercise_of_rights","reason","Synthetic specific evidence required to resolve the recorded dispute","caseReference","Synthetic-case-2026","reviewAt",Instant.now().plusSeconds(86400).toString(),"expiresAt",Instant.now().plusSeconds(172800).toString()));}
    private JsonNode hold(Session admin,Session owner,String category,UUID resource)throws Exception {var result=send("POST","/api/v1/admin/privacy/holds",createBody(owner,category,resource),admin,key());assertEquals(201,result.statusCode(),result.body());return tree(result);}
    private UUID id(JsonNode hold){return UUID.fromString(hold.path("id").asText());}
    private Map<String,Object> reviewBody(int revision,boolean release){var body=new LinkedHashMap<String,Object>(Map.of("password",PASSWORD,"expectedRevision",revision,"action",release?"release":"review","reason","Synthetic concrete updated assessment of this evidence case","caseReference","Synthetic-case-reviewed"));if(!release){body.put("reviewAt",Instant.now().plusSeconds(172800).toString());body.put("expiresAt",Instant.now().plusSeconds(259200).toString());}return body;}
    private void release(Session admin,JsonNode hold)throws Exception {var response=send("POST","/api/v1/admin/privacy/holds/"+id(hold)+"/review",reviewBody(hold.path("revision").asInt(),true),admin,key());assertEquals(200,response.statusCode(),response.body());}
    private UUID file(Session owner)throws Exception {UUID id=UUID.randomUUID();sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,'identity',?,'image/png',4,?)",id,owner.id(),id.toString(),"a".repeat(64));app.getBean(PrivateFileStorage.class).put(id.toString(),new byte[]{1,2,3,4});return id;}
    private void pendingErase(UUID file){sql.update("UPDATE stored_files SET deleted_at=now(),erasure_requested_at=now() WHERE id=?",file);}
    private void waitForAdvisoryWait()throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND wait_event='advisory' AND pid<>pg_backend_pid())",Boolean.class)))return;Thread.sleep(10);}fail("The competing operation never reached the database coordination lock.");}
    @Test void onlyAdminWithPasswordCanCreateAndReadEncryptedCaseEvidenceAndOwnViewIsMinimal()throws Exception {
        var admin=user(true);var owner=user(false);var peer=user(false);var body=createBody(owner,"profile",owner.id());
        assertEquals(401,send("POST","/api/v1/admin/privacy/holds",body,null,key()).statusCode());assertEquals(403,send("POST","/api/v1/admin/privacy/holds",body,owner,key()).statusCode());
        assertEquals(403,send("POST","/api/v1/admin/privacy/holds",body,new Session(admin.id(),admin.cookie(),"wrong"),key()).statusCode());body.put("password","wrong");assertEquals(401,send("POST","/api/v1/admin/privacy/holds",body,admin,key()).statusCode());body.put("password",PASSWORD);
        String key=key();var result=send("POST","/api/v1/admin/privacy/holds",body,admin,key);assertEquals(201,result.statusCode(),result.body());assertEquals(result.body(),send("POST","/api/v1/admin/privacy/holds",body,admin,key).body());UUID id=UUID.fromString(tree(result).path("id").asText());
        String cipher=sql.queryForObject("SELECT reason_ciphertext FROM privacy_legal_holds WHERE id=?",String.class,id);assertTrue(cipher.startsWith("pvc1."));assertFalse(cipher.contains((String)body.get("reason")));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_hold_events WHERE hold_id=?",Integer.class,id));
        var list=send("GET","/api/v1/admin/privacy/holds?userId="+owner.id(),null,admin,null);assertEquals(200,list.statusCode());assertFalse(list.body().contains("Synthetic-case"));
        assertEquals(403,send("POST","/api/v1/admin/privacy/holds/"+id+"/evidence",Map.of("password",PASSWORD),owner,null).statusCode());var detail=send("POST","/api/v1/admin/privacy/holds/"+id+"/evidence",Map.of("password",PASSWORD),admin,null);assertEquals(200,detail.statusCode(),detail.body());assertEquals(body.get("reason"),tree(detail).path("reason").asText());assertEquals(1,tree(detail).path("history").size());
        assertEquals(1,tree(send("GET","/api/v1/privacy/retentions",null,owner,null)).path("items").size());assertEquals(0,tree(send("GET","/api/v1/privacy/retentions",null,peer,null)).path("items").size());assertFalse(send("GET","/api/v1/privacy/retentions",null,owner,null).body().contains("Synthetic-case"));
        assertFalse(sql.queryForObject("SELECT coalesce(string_agg(metadata::text,''),'') FROM audit_logs WHERE resource_id=?",String.class,id).contains((String)body.get("reason")));
    }
    @Test void resourceOwnershipSpecificScopeAndBoundedDatesAreRequired()throws Exception {
        var admin=user(true);var owner=user(false);var peer=user(false);UUID file=file(peer);var body=createBody(owner,"files",file);assertEquals(409,send("POST","/api/v1/admin/privacy/holds",body,admin,key()).statusCode());
        body=createBody(owner,"profile",owner.id());body.remove("resourceId");assertEquals(422,send("POST","/api/v1/admin/privacy/holds",body,admin,key()).statusCode());
        body=createBody(owner,"profile",owner.id());body.put("reviewAt",Instant.now().plus(Duration.ofDays(91)).toString());body.put("expiresAt",Instant.now().plus(Duration.ofDays(100)).toString());assertEquals(422,send("POST","/api/v1/admin/privacy/holds",body,admin,key()).statusCode());
        body=createBody(owner,"profile",owner.id());body.put("expiresAt",Instant.now().plus(Duration.ofDays(366)).toString());assertEquals(422,send("POST","/api/v1/admin/privacy/holds",body,admin,key()).statusCode());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE user_id=?",Integer.class,owner.id()));assertEquals(422,send("GET","/api/v1/admin/privacy/holds?userId=invalid",null,admin,null).statusCode());
    }
    @Test void reviewUsesRevisionAndIdempotencyAndReleaseNeverReactivatesTheHold()throws Exception {
        var admin=user(true);var owner=user(false);var hold=hold(admin,owner,"profile",owner.id());var body=reviewBody(1,false);String key=key(),url="/api/v1/admin/privacy/holds/"+id(hold)+"/review";var reviewed=send("POST",url,body,admin,key);assertEquals(200,reviewed.statusCode(),reviewed.body());assertEquals(2,tree(reviewed).path("revision").asInt());
        assertEquals(409,send("POST",url,reviewBody(1,true),admin,key()).statusCode());release(admin,tree(reviewed));assertEquals(reviewed.body(),send("POST",url,body,admin,key).body());
        assertEquals(409,send("POST",url,reviewBody(3,false),admin,key()).statusCode());assertEquals(3,sql.queryForObject("SELECT count(*) FROM privacy_hold_events WHERE hold_id=?",Integer.class,id(hold)));assertEquals("released",tree(send("POST","/api/v1/admin/privacy/holds/"+id(hold)+"/evidence",Map.of("password",PASSWORD),admin,null)).path("hold").path("status").asText());
        assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_hold_events SET action='created' WHERE hold_id=? AND revision=2",id(hold)));assertThrows(RuntimeException.class,()->sql.update("DELETE FROM privacy_hold_events WHERE hold_id=?",id(hold)));
    }
    @Test void aHoldCreatedAfterCandidateSelectionStillPreventsPhysicalDeletion()throws Exception {
        var admin=user(true);var owner=user(false);UUID file=file(owner);pendingErase(file);assertTrue(app.getBean(PrivacyErasureTransactions.class).files(100).stream().anyMatch(f->f.id().equals(file)));
        var hold=hold(admin,owner,"files",file);assertFalse(app.getBean(PrivacyFileErasure.class).erase(file));assertTrue(Files.exists(storage.resolve(file.toString())));assertNull(sql.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,file));
        release(admin,hold);assertTrue(app.getBean(PrivacyFileErasure.class).erase(file));assertFalse(Files.exists(storage.resolve(file.toString())));assertEquals(409,send("POST","/api/v1/admin/privacy/holds",createBody(owner,"files",file),admin,key()).statusCode());
    }
    @Test void anErasureInProgressFinishesBeforeTheNewHoldCanClaimToProtectItsMissingFile()throws Exception {
        var admin=user(true);var owner=user(false);UUID file=file(owner);pendingErase(file);var storageBean=app.getBean(BlockingStorage.class);var gate=new Gate(file.toString(),new CountDownLatch(1),new CountDownLatch(1));storageBean.gate.set(gate);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var erased=executor.submit(()->app.getBean(PrivacyFileErasure.class).erase(file));assertTrue(gate.entered().await(5,TimeUnit.SECONDS));
            var created=executor.submit(()->send("POST","/api/v1/admin/privacy/holds",createBody(owner,"files",file),admin,key()));
            try{waitForAdvisoryWait();assertFalse(created.isDone());}finally{gate.release().countDown();}
            assertTrue(erased.get(5,TimeUnit.SECONDS));assertEquals(409,created.get(5,TimeUnit.SECONDS).statusCode());assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE user_id=?",Integer.class,owner.id()));
        }finally{gate.release().countDown();storageBean.gate.set(null);}
    }
    @Test void aHoldTransactionCommitsBeforeTheWaitingFileErasureRechecksItsProtection()throws Exception {
        var admin=user(true);var owner=user(false);UUID file=file(owner);pendingErase(file);var inserted=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var held=executor.submit(()->new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).execute(status->{sql.update("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'files','stored_file',?,'regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",owner.id(),file,app.getBean(CryptoService.class).encrypt("Synthetic concurrent case",AuthTestSettings.KEY),admin.id());inserted.countDown();try{if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic commit gate timeout.");}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic commit interrupted.");}return true;}));
            assertTrue(inserted.await(5,TimeUnit.SECONDS));var erased=executor.submit(()->app.getBean(PrivacyFileErasure.class).erase(file));try{waitForAdvisoryWait();assertFalse(erased.isDone());}finally{commit.countDown();}
            assertTrue(held.get(5,TimeUnit.SECONDS));assertFalse(erased.get(5,TimeUnit.SECONDS));assertTrue(Files.exists(storage.resolve(file.toString())));
        }finally{commit.countDown();}
    }
    @Test void heldProfileAndNotificationArePrivateCopiesAndReleaseRemovesOnlyHoldOnlyEvidence()throws Exception {
        var admin=user(true);var owner=user(false);UUID notice=sql.queryForObject("INSERT INTO notifications(user_id,title,body,route) VALUES(?,'Synthetic held notice','Synthetic case-only content','settings') RETURNING id",UUID.class,owner.id());var profileHold=hold(admin,owner,"profile",owner.id());var notificationHold=hold(admin,owner,"notifications",notice);
        String version=tree(send("GET","/api/v1/privacy/closure",null,owner,null)).path("policyVersion").asText();var closed=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),owner,key());assertEquals(202,closed.statusCode(),closed.body());UUID closure=UUID.fromString(tree(closed).path("id").asText());
        sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE id=?",closure);app.getBean(PrivacyErasureMaintenance.class).run(100,()->false);
        assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,owner.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,owner.id()));assertEquals(2,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND hold_only",Integer.class,owner.id()));
        var copied=send("POST","/api/v1/admin/privacy/holds/"+id(profileHold)+"/data",Map.of("password",PASSWORD),admin,null);assertEquals(200,copied.statusCode(),copied.body());assertTrue(copied.headers().firstValue("cache-control").orElse("").contains("no-store"));assertEquals("Synthetic held profile",tree(copied).path("records").get(0).path("data").path("name").asText());
        sql.update("INSERT INTO privacy_retained_records(user_id,category,content_ciphertext,reason,legal_basis,delete_after) VALUES(?,'contract_identity',?,'Synthetic independent financial basis','regular_exercise_of_rights',now()+interval '1 year')",owner.id(),app.getBean(CryptoService.class).encrypt("Synthetic financial identity",AuthTestSettings.KEY));
        release(admin,profileHold);release(admin,notificationHold);app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND hold_only",Integer.class,owner.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND NOT hold_only",Integer.class,owner.id()));
        assertEquals(409,send("POST","/api/v1/admin/privacy/holds/"+id(profileHold)+"/data",Map.of("password",PASSWORD),admin,null).statusCode());
    }
    @Test void oneNotificationHoldDoesNotKeepUnrelatedExpiredNotifications()throws Exception {
        var admin=user(true);var owner=user(false);var ids=new ArrayList<UUID>();for(int n=0;n<2;n++)ids.add(sql.queryForObject("INSERT INTO notifications(user_id,title,body,route,created_at) VALUES(?,'Synthetic old notice','Synthetic old body','settings',now()-interval '91 days') RETURNING id",UUID.class,owner.id()));
        var hold=hold(admin,owner,"notifications",ids.getFirst());app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,owner.id()));assertEquals(ids.getFirst(),sql.queryForObject("SELECT id FROM notifications WHERE user_id=?",UUID.class,owner.id()));release(admin,hold);app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Integer.class,owner.id()));
    }
    @Test void overdueReviewNeverExtendsExpiryAndExpiredHoldCannotBeRenewed()throws Exception {
        var admin=user(true);var owner=user(false);var hold=hold(admin,owner,"profile",owner.id());UUID id=id(hold);sql.update("UPDATE privacy_legal_holds SET created_at=now()-interval '3 days',review_at=now()-interval '1 day' WHERE id=?",id);
        var list=send("GET","/api/v1/admin/privacy/holds?userId="+owner.id()+"&status=review_due",null,admin,null);assertEquals(1,tree(list).path("items").size());String before=sql.queryForObject("SELECT expires_at::text FROM privacy_legal_holds WHERE id=?",String.class,id);app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(before,sql.queryForObject("SELECT expires_at::text FROM privacy_legal_holds WHERE id=?",String.class,id));
        sql.update("UPDATE privacy_legal_holds SET expires_at=now()-interval '1 hour' WHERE id=?",id);assertEquals(409,send("POST","/api/v1/admin/privacy/holds/"+id+"/review",reviewBody(1,false),admin,key()).statusCode());assertEquals(0,tree(send("GET","/api/v1/privacy/retentions",null,owner,null)).path("items").size());
    }
    @Test void privateHeldFileDownloadRequiresAnActiveHoldAdminPasswordAndCreatesNoPublicLink()throws Exception {
        var admin=user(true);var owner=user(false);UUID file=file(owner);var hold=hold(admin,owner,"files",file);String path="/api/v1/admin/privacy/holds/"+id(hold)+"/data";
        assertEquals(404,send("GET",path,null,admin,null).statusCode());assertEquals(403,send("POST",path,Map.of("password",PASSWORD),owner,null).statusCode());assertEquals(401,send("POST",path,Map.of("password","wrong"),admin,null).statusCode());
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json").header("Cookie",admin.cookie()).header("x-csrf-token",admin.csrf()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("password",PASSWORD)))).build();var result=http.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,result.statusCode());assertArrayEquals(new byte[]{1,2,3,4},result.body());assertTrue(result.headers().firstValue("content-disposition").orElse("").startsWith("attachment;"));assertTrue(result.headers().firstValue("cache-control").orElse("").contains("no-store"));
        release(admin,hold);assertEquals(409,send("POST",path,Map.of("password",PASSWORD),admin,null).statusCode());assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='privacy_held_data_accessed' AND resource_id=?",Integer.class,id(hold)));
    }
    @Test void firstReviewOfLegacyHoldPreservesItsKnownStateWithoutInventingAnOriginalAuditEvent()throws Exception {
        var admin=user(true);var owner=user(false);String original="Synthetic original legacy justification for the specific case";UUID hold=sql.queryForObject("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'profile','user',?,'regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days') RETURNING id",UUID.class,owner.id(),owner.id(),app.getBean(CryptoService.class).encrypt(original,AuthTestSettings.KEY),admin.id());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_hold_events WHERE hold_id=?",Integer.class,hold));var reviewed=send("POST","/api/v1/admin/privacy/holds/"+hold+"/review",reviewBody(1,false),admin,key());assertEquals(200,reviewed.statusCode(),reviewed.body());
        var detail=tree(send("POST","/api/v1/admin/privacy/holds/"+hold+"/evidence",Map.of("password",PASSWORD),admin,null));assertEquals(2,detail.path("history").size());assertEquals("legacy_snapshot",detail.path("history").get(0).path("action").asText());assertEquals(original,detail.path("history").get(0).path("reason").asText());assertEquals("reviewed",detail.path("history").get(1).path("action").asText());
        assertThrows(RuntimeException.class,()->sql.update("INSERT INTO privacy_hold_events(hold_id,user_id,revision,action,body_ciphertext,created_by) VALUES(?,?,3,'reviewed','synthetic-not-a-real-cipher',?)",hold,admin.id(),admin.id()));
    }
    @Test void aSpecificLegalReceiptHoldDoesNotKeepOtherExpiredReceiptsOrNotices()throws Exception {
        var admin=user(true);var owner=user(false);sql.update("UPDATE users SET account_status='deleted',deleted_at=now()-interval '6 years',password_hash='disabled' WHERE id=?",owner.id());var ids=new ArrayList<UUID>();var slugs=new ArrayList<String>();
        for(int n=0;n<2;n++){String slug="held-test-"+UUID.randomUUID().toString().replace("-","");slugs.add(slug);sql.update("INSERT INTO legal_document_versions(slug,version,title,content,content_sha256,change_summary,requires_acceptance,effective_at) VALUES(?,'test-v1','Synthetic retained document','{}',?,'Synthetic initial document',true,now())",slug,"c".repeat(64));ids.add(sql.queryForObject("INSERT INTO legal_acceptances(user_id,document_slug,document_version,kind,content_sha256,source,accepted_at,retain_until) VALUES(?,?,'test-v1','contract',?,'registration',now()-interval '6 years',now()-interval '1 day') RETURNING id",UUID.class,owner.id(),slug,"c".repeat(64)));sql.update("INSERT INTO legal_change_notices(user_id,document_slug,document_version,notified_at) VALUES(?,?,'test-v1',now()-interval '6 years')",owner.id(),slug);}
        var hold=hold(admin,owner,"legal",ids.getFirst());app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(1,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,owner.id()));assertEquals(ids.getFirst(),sql.queryForObject("SELECT id FROM legal_acceptances WHERE user_id=?",UUID.class,owner.id()));assertEquals(slugs.getFirst(),sql.queryForObject("SELECT document_slug FROM legal_change_notices WHERE user_id=?",String.class,owner.id()));release(admin,hold);app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_acceptances WHERE user_id=?",Integer.class,owner.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM legal_change_notices WHERE user_id=?",Integer.class,owner.id()));
    }
    @Test void allStatusAdminQueuesUseAnOmittedStatusInsteadOfAnEmptyEnum()throws Exception {
        var admin=user(true);var owner=user(false);var created=hold(admin,owner,"profile",owner.id());
        var listed=send("GET","/api/v1/admin/privacy/holds?limit=30&offset=0&userId="+owner.id(),null,admin,null);
        assertEquals(200,listed.statusCode(),listed.body());assertEquals(id(created).toString(),tree(listed).path("items").get(0).path("id").asText());
        assertEquals(200,send("GET","/api/v1/admin/privacy/holds?limit=30&offset=0&status=active",null,admin,null).statusCode());
        assertEquals(200,send("GET","/api/v1/admin/privacy/holds?limit=30&offset=0&status=review_due",null,admin,null).statusCode());
        assertEquals(422,send("GET","/api/v1/admin/privacy/holds?limit=30&offset=0&status=",null,admin,null).statusCode());
        assertEquals(200,send("GET","/api/v1/admin/privacy/requests?limit=30&offset=0",null,admin,null).statusCode());
        assertEquals(200,send("GET","/api/v1/admin/privacy/requests?limit=30&offset=0&status=received",null,admin,null).statusCode());
        assertEquals(422,send("GET","/api/v1/admin/privacy/requests?limit=30&offset=0&status=",null,admin,null).statusCode());
    }
    @Test void aMutableProfileIsSnapshottedAtCreationWhileTheOwnerCanStillCorrectTheCurrentData()throws Exception {
        var admin=user(true);var owner=user(false);var hold=hold(admin,owner,"profile",owner.id());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND category='profile' AND hold_only",Integer.class,owner.id()));
        var corrected=send("POST","/api/v1/privacy/identity/birth",Map.of("password",PASSWORD,"birth","2001-02-03"),owner,key());assertEquals(200,corrected.statusCode(),corrected.body());assertEquals("2001-02-03",sql.queryForObject("SELECT birth_date::text FROM users WHERE id=?",String.class,owner.id()));
        var snapshot=send("POST","/api/v1/admin/privacy/holds/"+id(hold)+"/data",Map.of("password",PASSWORD),admin,null);assertEquals(200,snapshot.statusCode(),snapshot.body());assertEquals("2000-01-01",tree(snapshot).path("records").get(0).path("data").path("birth_date").asText());
        var exported=http.send(HttpRequest.newBuilder(URI.create(server+"/api/v1/privacy/export")).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json").header("Cookie",owner.cookie()).header("x-csrf-token",owner.csrf()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("password",PASSWORD)))).build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,exported.statusCode());try(var zip=new ZipInputStream(new ByteArrayInputStream(exported.body()))){assertEquals("dados.json",zip.getNextEntry().getName());var data=json.readTree(zip.readAllBytes());assertEquals("2001-02-03",data.path("profile").get(0).path("birth_date").asText());assertEquals("2000-01-01",data.path("retainedIdentityCopies").get(0).path("content").path("birth_date").asText());assertFalse(data.toString().contains("Synthetic-case-2026"));}
        release(admin,hold);app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND category='profile'",Integer.class,owner.id()));assertEquals("2001-02-03",sql.queryForObject("SELECT birth_date::text FROM users WHERE id=?",String.class,owner.id()));
    }
}
