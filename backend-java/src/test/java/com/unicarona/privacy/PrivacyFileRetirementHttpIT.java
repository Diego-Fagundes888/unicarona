package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.file.repository.FileAccessRepository;
import com.unicarona.file.service.FileService;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.*;
import com.unicarona.shared.ApiException;
import com.unicarona.support.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, physical private files and actual HTTP reads; all account data is synthetic. */
class PrivacyFileRetirementHttpIT {
    @TempDir static Path temporary;
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;
    private static Path storage;private static String server,hash;
    private static final String PASSWORD="Synthetic file retirement test 2026";
    private final HttpClient http=HttpClient.newHttpClient();private final ObjectMapper json=new ObjectMapper();
    private record Session(UUID id,String cookie,String csrf){@Override public String toString(){return "FileRetirementSession[redacted]";}}
    @BeforeAll static void start()throws Exception {
        db=new RealPostgres();db.resetSchema();storage=temporary.resolve("uploads");var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));
        args.addAll(List.of("--STORAGE_PATH="+storage,"--FRONTEND_PATH="+temporary.resolve("public"),"--STORAGE_USER_MAX_FILES=2","--PRIVACY_ERASURE_JOURNAL_PATH="+temporary.resolve("journal"),"--PRIVACY_OPERATIONS_ROOT="+temporary.resolve("operations"),"--PRIVACY_BACKUP_KEY_ID=b1","--PRIVACY_BACKUP_KEYS={\"b1\":\""+"A".repeat(43)+"\"}","--PRIVACY_JOURNAL_KEY_ID=j1","--PRIVACY_JOURNAL_KEYS={\"j1\":\""+"B".repeat(43)+"\"}"));
        var env=new MockEnvironment();for(String arg:args){int equal=arg.indexOf('=');env.withProperty(arg.substring(2,equal),arg.substring(equal+1));}
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);var keys=new PrivacySigningKeys(env,auth,crypto,mapper);
        new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),keys,mapper,Clock.systemUTC()).initialize();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));sql=app.getBean(JdbcTemplate.class);hash=crypto.hashPassword(PASSWORD);server="http://127.0.0.1:"+app.getWebServer().getPort();
    }
    @AfterAll static void close(){if(app!=null)app.close();if(db!=null)db.close();}
    @BeforeEach void limits(){sql.update("DELETE FROM rate_limits");}
    private HttpResponse<String> send(String method,String path,Object body,Session session)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");if(session!=null)request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());
        if(!"GET".equals(method))request.header("idempotency-key",UUID.randomUUID().toString());
        request.method(method,"GET".equals(method)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private Session user(boolean admin)throws Exception {
        String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at,role) VALUES(?,?,'Synthetic active file owner','2000-01-01',now(),?) RETURNING id",UUID.class,email,hash,admin?"admin":"user");
        var result=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null);assertEquals(200,result.statusCode(),result.body());var cookies=new LinkedHashMap<String,String>();for(String header:result.headers().allValues("set-cookie")){var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}return new Session(id,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private UUID file(Session owner,String purpose,boolean old)throws Exception {
        byte[] bytes={1,2,3,4};UUID id=UUID.randomUUID();String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256,created_at,last_uploaded_at) VALUES(?,?,?,?,'application/pdf',4,?,now()-CASE WHEN ? THEN interval '2 days' ELSE interval '0 days' END,now()-CASE WHEN ? THEN interval '2 days' ELSE interval '0 days' END)",id,owner.id(),purpose,id.toString(),sha,old,old);app.getBean(PrivateFileStorage.class).put(id.toString(),bytes);return id;
    }
    private PrivacyUnusedFiles.Candidate candidate(Session owner,UUID file){return new PrivacyUnusedFiles.Candidate(file,owner.id());}
    private void retire(Session owner,UUID file){assertTrue(app.getBean(PrivacyUnusedFiles.class).retire(candidate(owner,file)));}
    private void erase(UUID file)throws Exception {assertTrue(app.getBean(PrivacyFileErasure.class).erase(file));assertFalse(Files.exists(storage.resolve(file.toString())));}
    private void guard()throws Exception {app.getBean(PrivacyRestoreGuard.class).run(new DefaultApplicationArguments());}
    private JsonNode hold(Session admin,Session owner,UUID file)throws Exception {
        var body=Map.of("password",PASSWORD,"userId",owner.id(),"category","files","resourceId",file,"legalBasis","regular_exercise_of_rights","reason","Synthetic specific dispute requires preserving this uploaded file","caseReference","Synthetic file case","reviewAt",Instant.now().plusSeconds(86400).toString(),"expiresAt",Instant.now().plusSeconds(172800).toString());
        var result=send("POST","/api/v1/admin/privacy/holds",body,admin);assertEquals(201,result.statusCode(),result.body());return json.readTree(result.body());
    }
    private void release(Session admin,JsonNode hold)throws Exception {
        var result=send("POST","/api/v1/admin/privacy/holds/"+hold.path("id").asText()+"/review",Map.of("password",PASSWORD,"expectedRevision",hold.path("revision").asInt(),"action","release","reason","Synthetic dispute resolved and this file no longer requires preservation","caseReference","Synthetic closed file case"),admin);assertEquals(200,result.statusCode(),result.body());
    }
    @Test void onlyUnreferencedOldUploadsAreRetiredWhileCurrentPhotoFreshUploadsAndDriverDocumentsRemain()throws Exception {
        var owner=user(false);UUID abandoned=file(owner,"identity",true),fresh=file(owner,"vehicle",false),photo=file(owner,"photo",true),identity=file(owner,"identity",true),license=file(owner,"license",true);
        sql.update("UPDATE users SET photo_file_id=? WHERE id=?",photo,owner.id());UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic','Black','ABC1D23',4,'pending') RETURNING id",UUID.class,owner.id());sql.update("INSERT INTO driver_applications(user_id,vehicle_id,identity_file_id,license_file_id) VALUES(?,?,?,?)",owner.id(),vehicle,identity,license);
        var service=app.getBean(PrivacyUnusedFiles.class);var ids=service.candidates(10000).stream().filter(c->c.owner().equals(owner.id())).map(PrivacyUnusedFiles.Candidate::id).toList();assertEquals(List.of(abandoned),ids);
        retire(owner,abandoned);erase(abandoned);for(UUID kept:List.of(fresh,photo,identity,license)){assertFalse(service.retire(candidate(owner,kept)));assertTrue(Files.exists(storage.resolve(kept.toString())));assertEquals(200,send("GET","/api/v1/files/"+kept,null,owner).statusCode());}
        assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner.id()));
    }
    @Test void aHoldAddedAfterSelectionIsRecheckedAndDoesNotStarveOtherCandidates()throws Exception {
        var admin=user(true);var owner=user(false);UUID held=file(owner,"identity",true),other=file(owner,"vehicle",true);var service=app.getBean(PrivacyUnusedFiles.class);var selected=candidate(owner,held);var h=hold(admin,owner,held);
        assertFalse(service.retire(selected));assertFalse(service.candidates(10000).stream().anyMatch(c->c.id().equals(held)));assertTrue(service.candidates(10000).stream().anyMatch(c->c.id().equals(other)));assertTrue(Files.exists(storage.resolve(held.toString())));
        release(admin,h);retire(owner,held);erase(held);
    }
    @Test void fileBecomingCurrentWhileTheCleanerWaitsForItsOwnerIsNotRetired()throws Exception {
        var owner=user(false);UUID photo=file(owner,"photo",true);var selected=candidate(owner,photo);var locked=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var linking=executor.submit(()->new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).execute(status->{sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner.id());sql.update("UPDATE users SET photo_file_id=? WHERE id=?",photo,owner.id());locked.countDown();try{assertTrue(commit.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));var retiring=executor.submit(()->app.getBean(PrivacyUnusedFiles.class).retire(selected));try{assertThrows(TimeoutException.class,()->retiring.get(100,TimeUnit.MILLISECONDS));}finally{commit.countDown();}
            assertTrue(linking.get(5,TimeUnit.SECONDS));assertFalse(retiring.get(5,TimeUnit.SECONDS));assertTrue(Files.exists(storage.resolve(photo.toString())));
        }finally{commit.countDown();}
    }
    @Test void quotaIsReleasedOnlyAfterVerifiedPhysicalErasureAndTheOwnerRemainsActive()throws Exception {
        var owner=user(false);UUID old=file(owner,"identity",true);file(owner,"vehicle",false);var service=app.getBean(FileService.class);var actor=new Actor(owner.id(),"user","active",null,null,null,true,Set.of());byte[] upload="%PDF-1.7\nSynthetic replacement document\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        assertEquals("FILE_QUOTA",assertThrows(ApiException.class,()->service.upload(actor,"identity",upload)).code());retire(owner,old);
        assertEquals(2,app.getBean(FileAccessRepository.class).usage(owner.id()).count());assertEquals("FILE_QUOTA",assertThrows(ApiException.class,()->service.upload(actor,"identity",upload)).code());erase(old);
        assertEquals(1,app.getBean(FileAccessRepository.class).usage(owner.id()).count());var created=service.upload(actor,"identity",upload);assertTrue(Files.exists(storage.resolve(created.id().toString())));assertEquals(200,send("GET","/api/v1/auth/me",null,owner).statusCode());
    }
    @Test void anOldDatabaseAndFileRestoreCannotResurrectTheOldPhotoOrDeleteTheActiveAccount()throws Exception {
        var owner=user(false);UUID old=file(owner,"photo",true),kept=file(owner,"identity",false);retire(owner,old);erase(old);
        sql.update("UPDATE stored_files SET deleted_at=NULL,erasure_requested_at=NULL,erased_at=NULL,sha256=? WHERE id=?","b".repeat(64),old);sql.update("UPDATE users SET photo_file_id=? WHERE id=?",old,owner.id());app.getBean(PrivateFileStorage.class).put(old.toString(),new byte[]{9,8,7});
        guard();assertFalse(Files.exists(storage.resolve(old.toString())));assertTrue(Files.exists(storage.resolve(kept.toString())));assertNull(sql.queryForObject("SELECT photo_file_id FROM users WHERE id=?",UUID.class,owner.id()));assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner.id()));assertEquals(404,send("GET","/api/v1/files/"+old,null,owner).statusCode());assertEquals(200,send("GET","/api/v1/auth/me",null,owner).statusCode());assertTrue(app.getBean(PrivacyErasureJournal.class).entries().isEmpty());
    }
    @Test void fileOnlyRestoreIsSuppressedEvenWhenTheDatabaseRowWasAbsentInTheSnapshot()throws Exception {
        var owner=user(false);UUID old=file(owner,"identity",true);retire(owner,old);erase(old);sql.update("DELETE FROM stored_files WHERE id=?",old);app.getBean(PrivateFileStorage.class).put(old.toString(),new byte[]{9});guard();assertFalse(Files.exists(storage.resolve(old.toString())));assertTrue(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void aPhysicalFailureKeepsIndependentEvidenceAndPreventsReadinessUntilSuppressionSucceeds()throws Exception {
        var owner=user(false);UUID old=file(owner,"identity",true);retire(owner,old);Path path=storage.resolve(old.toString());Files.delete(path);Files.createDirectory(path);Path obstruction=path.resolve("synthetic-obstruction");Files.writeString(obstruction,"Synthetic bytes");
        assertThrows(Exception.class,()->app.getBean(PrivacyFileErasure.class).erase(old));assertTrue(app.getBean(PrivacyErasureJournal.class).fileEntries().stream().anyMatch(e->e.file().equals(old)));assertNull(sql.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,old));
        assertThrows(Exception.class,this::guard);assertFalse(app.getBean(PrivacyReadiness.class).ready());Files.delete(obstruction);Files.delete(path);app.getBean(PrivateFileStorage.class).put(old.toString(),new byte[]{7});guard();assertTrue(app.getBean(PrivacyReadiness.class).ready());assertFalse(Files.exists(path));
    }
    @Test void replayPreservesAnActiveCaseButKeepsTheRestoredFileUnavailableToOrdinaryDownloads()throws Exception {
        var admin=user(true);var owner=user(false);UUID old=file(owner,"identity",true);retire(owner,old);var entry=app.getBean(PrivacyErasureJournal.class).ensureFile(owner.id(),old,Instant.now(),Instant.now().plus(Duration.ofDays(37)));var h=hold(admin,owner,old);
        assertFalse(app.getBean(PrivacyFileErasure.class).replay(entry));assertTrue(Files.exists(storage.resolve(old.toString())));assertEquals(404,send("GET","/api/v1/files/"+old,null,owner).statusCode());release(admin,h);erase(old);
    }
    @Test void reuploadingAnOldDeduplicatedDocumentRestartsItsAllowanceBeforeTheFormIsSubmitted()throws Exception {
        var owner=user(false);var actor=new Actor(owner.id(),"user","active",null,null,null,true,Set.of());var service=app.getBean(FileService.class);byte[] source="%PDF-1.7\nSynthetic document uploaded again before applying\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        UUID id=service.upload(actor,"identity",source).id();sql.update("UPDATE stored_files SET created_at=now()-interval '2 days',last_uploaded_at=now()-interval '2 days' WHERE id=?",id);assertTrue(app.getBean(PrivacyUnusedFiles.class).candidates(10000).stream().anyMatch(c->c.id().equals(id)));
        assertEquals(id,service.upload(actor,"identity",source).id());assertFalse(app.getBean(PrivacyUnusedFiles.class).retire(candidate(owner,id)));assertTrue(Files.exists(storage.resolve(id.toString())));
    }
    @Test void springOrderedMaintenancePreparesUnusedUploadsBeforeThePhysicalErasurePass()throws Exception {
        var owner=user(false);UUID old=file(owner,"identity",true);assertEquals(404,send("GET","/api/v1/files/"+old,null,owner).statusCode(),"The allowance also limits ordinary reads before a cleanup pass");
        var maintenance=app.getBeanProvider(com.unicarona.job.service.MaintenanceTask.class).orderedStream().filter(task->task instanceof PrivacyUnusedFilesMaintenance||task instanceof PrivacyOrphanFiles||task instanceof PrivacyErasureMaintenance).toList();assertEquals(3,maintenance.size());assertInstanceOf(PrivacyUnusedFilesMaintenance.class,maintenance.getFirst());assertInstanceOf(PrivacyOrphanFiles.class,maintenance.get(1));assertInstanceOf(PrivacyErasureMaintenance.class,maintenance.getLast());
        for(var task:maintenance)task.run(10000,()->false);
        assertFalse(Files.exists(storage.resolve(old.toString())));assertEquals(404,send("GET","/api/v1/files/"+old,null,owner).statusCode());assertEquals(0,app.getBean(FileAccessRepository.class).usage(owner.id()).count());assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner.id()));
    }
}
