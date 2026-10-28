package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.security.CryptoService;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyRightsHttpIT {
    private static RealPostgres db;private static ServletWebServerApplicationContext app;private static JdbcTemplate sql;private static String server,passwordHash;private static Path storagePath,journalPath;
    private static final String PASSWORD="Synthetic privacy password 2026";
    private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();
    private record Session(UUID id,String cookie,String csrf){}
    @BeforeAll static void start()throws Exception {
        db=new RealPostgres();db.resetSchema();storagePath=Files.createTempDirectory("unicarona-privacy-export-");journalPath=Files.createTempDirectory("unicarona-privacy-journal-");var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));args.add("--STORAGE_PATH="+storagePath);args.add("--PRIVACY_ERASURE_JOURNAL_PATH="+journalPath);
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
    }
    @AfterAll static void close()throws Exception {if(app!=null)app.close();if(db!=null)db.close();cleanup(storagePath);cleanup(journalPath);}
    private static void cleanup(Path root)throws IOException {if(root==null||!Files.exists(root))return;assertTrue(root.toAbsolutePath().normalize().getFileName().toString().startsWith("unicarona-privacy-"));List<Path> files;try(var walk=Files.walk(root)){files=walk.sorted(Comparator.reverseOrder()).toList();}for(Path file:files){assertTrue(file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));Files.delete(file);}}
    @BeforeEach void limits(){sql.update("DELETE FROM rate_limits");}
    private Session session()throws Exception {
        String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic privacy user','2000-01-01',now()) RETURNING id",UUID.class,email,passwordHash);
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null,null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")){String[] pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}
        return new Session(id,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private HttpResponse<String> send(String method,String path,Object body,Session session,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");
        if(session!=null)request.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());if(key!=null)request.header("idempotency-key",key);
        request.method(method,"GET".equals(method)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode tree(HttpResponse<String> response)throws Exception{return json.readTree(response.body());}
    @Test void reauthenticationReportsActualRetryWithoutExtendingThePersistentWindow()throws Exception {
        var owner=session();
        for(int i=0;i<5;i++)assertEquals(401,send("POST","/api/v1/privacy/export",Map.of("password","incorrect synthetic password"),owner,null).statusCode());
        String bucket=app.getBean(CryptoService.class).hmac("privacy-reauth:"+owner.id(),app.getBean(com.unicarona.auth.config.AuthSettings.class).pepper());
        var expires=sql.queryForObject("SELECT expires_at FROM rate_limits WHERE bucket=?",java.sql.Timestamp.class,bucket);
        assertEquals(5,sql.queryForObject("SELECT attempts FROM rate_limits WHERE bucket=?",Integer.class,bucket));
        var blocked=send("POST","/api/v1/privacy/export",Map.of("password",PASSWORD),owner,null);
        assertEquals(429,blocked.statusCode());var error=tree(blocked);assertEquals("RATE_LIMITED",error.path("code").asText());
        int remaining=error.path("details").path("retryAfterSeconds").asInt();assertTrue(remaining>0&&remaining<=900);
        assertEquals(Integer.toString(remaining),blocked.headers().firstValue("Retry-After").orElseThrow());assertTrue(error.path("message").asText().contains("minuto"));
        assertEquals(429,send("POST","/api/v1/privacy/export",Map.of("password",PASSWORD),owner,null).statusCode());
        assertEquals(expires,sql.queryForObject("SELECT expires_at FROM rate_limits WHERE bucket=?",java.sql.Timestamp.class,bucket));
        assertEquals(7,sql.queryForObject("SELECT attempts FROM rate_limits WHERE bucket=?",Integer.class,bucket));
        // Only this disposable test bucket is expired to prove the natural window reset.
        sql.update("UPDATE rate_limits SET expires_at=now()-interval '1 second' WHERE bucket=?",bucket);
        assertEquals(200,send("POST","/api/v1/privacy/export",Map.of("password",PASSWORD),owner,null).statusCode());
        assertEquals(1,sql.queryForObject("SELECT attempts FROM rate_limits WHERE bucket=?",Integer.class,bucket));
    }
    @Test void preferencesRequireAuthenticationStartOffAndRevocationDoesNotRequireNewConsent()throws Exception {
        assertEquals(401,send("GET","/api/v1/privacy/preferences",null,null,null).statusCode());var a=session();
        var current=tree(send("GET","/api/v1/privacy/preferences",null,a,null));assertFalse(current.path("marketing").asBoolean());
        assertEquals(409,send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",true,"statementVersion","obsolete"),a,null).statusCode());
        assertEquals(200,send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",true,"statementVersion",current.path("statementVersion").asText()),a,null).statusCode());
        assertEquals(200,send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",false),a,null).statusCode());
        assertFalse(tree(send("GET","/api/v1/privacy/preferences",null,a,null)).path("marketing").asBoolean());
        assertEquals(2,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=?",Integer.class,a.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=? AND NOT granted",Integer.class,a.id()));
    }
    @Test void requestIsEncryptedIdempotentAndOnlyVisibleToItsOwner()throws Exception {
        var a=session();var b=session();String text="Synthetic private correction details";String key=UUID.randomUUID().toString();
        var created=send("POST","/api/v1/privacy/requests",Map.of("kind","correction","details",text),a,key);assertEquals(202,created.statusCode(),created.body());String id=tree(created).path("id").asText();
        assertFalse(created.body().contains(text));assertEquals(created.body(),send("POST","/api/v1/privacy/requests",Map.of("kind","correction","details",text),a,key).body());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_requests WHERE user_id=?",Integer.class,a.id()));
        assertFalse(sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?::uuid",String.class,id).contains(text));
        String guessable=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(app.getBean(ObjectMapper.class).writeValueAsBytes(new com.unicarona.privacy.dto.PrivacyDto.Request("correction",text))));assertNotEquals(guessable,sql.queryForObject("SELECT body_hash FROM idempotency_keys WHERE actor_id=? AND key=? AND scope='privacy:request'",String.class,a.id(),key));
        assertEquals(404,send("GET","/api/v1/privacy/requests/"+id,null,b,null).statusCode());
        assertEquals(text,tree(send("GET","/api/v1/privacy/requests/"+id,null,a,null)).path("details").asText());
        assertEquals(0,tree(send("GET","/api/v1/privacy/requests",null,b,null)).path("items").size());
        assertFalse(sql.queryForObject("SELECT metadata::text FROM audit_logs WHERE action='privacy_request_received' AND actor_id=?",String.class,a.id()).contains(text));
    }
    @Test void cannotChooseAnotherUserAndSuspensionDoesNotRemovePrivacyRights()throws Exception {
        var a=session();assertEquals(422,send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Synthetic request","userId",UUID.randomUUID().toString()),a,UUID.randomUUID().toString()).statusCode());
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",a.id());
        assertEquals(202,send("POST","/api/v1/privacy/requests",Map.of("kind","appeal","details","Synthetic request for a review"),a,UUID.randomUUID().toString()).statusCode());
        assertEquals(200,send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",false),a,null).statusCode());
    }
    @Test void csrfStillAppliesAndRequestsAreBoundedPerAccount()throws Exception {
        var a=session();var noCsrf=new Session(a.id(),a.cookie(),"invalid");
        assertEquals(403,send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Synthetic request"),noCsrf,UUID.randomUUID().toString()).statusCode());
        for(int n=0;n<5;n++)assertEquals(202,send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Synthetic request "+n),a,UUID.randomUUID().toString()).statusCode());
        assertEquals(429,send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Synthetic excess request"),a,UUID.randomUUID().toString()).statusCode());
    }
    @Test void losingEmailVerificationKeepsOwnRightsButDoesNotUnlockBusinessOrAnotherPersonsData()throws Exception {
        var a=session();var b=session();
        String other=tree(send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Other synthetic rights request"),b,UUID.randomUUID().toString())).path("id").asText();
        sql.update("UPDATE users SET email_verified_at=NULL,account_status='suspended' WHERE id=?",a.id());
        assertEquals(200,send("GET","/api/v1/auth/me",null,a,null).statusCode());
        assertEquals(200,send("GET","/api/v1/privacy/preferences",null,a,null).statusCode());
        assertEquals(200,send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",false),a,null).statusCode());
        assertEquals(202,send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Synthetic access after loss of verification"),a,UUID.randomUUID().toString()).statusCode());
        assertEquals(404,send("GET","/api/v1/privacy/requests/"+other,null,a,null).statusCode());
        var noCsrf=new Session(a.id(),a.cookie(),"invalid");
        var denied=send("POST","/api/v1/privacy/requests",Map.of("kind","access","details","Synthetic invalid csrf"),noCsrf,UUID.randomUUID().toString());assertEquals(403,denied.statusCode());assertEquals("CSRF_INVALID",tree(denied).path("code").asText());
        assertEquals(401,send("POST","/api/v1/privacy/export",Map.of("password","wrong password"),a,null).statusCode());
        assertEquals(200,send("POST","/api/v1/privacy/export",Map.of("password",PASSWORD),a,null).statusCode());
        for(String path:List.of("/api/v1/rides?date=2030-01-01&direction=ida","/api/v1/files/"+UUID.randomUUID(),"/api/v1/admin/privacy/holds")) {
            var response=send("GET",path,null,a,null);assertEquals(403,response.statusCode());assertEquals("EMAIL_UNVERIFIED",tree(response).path("code").asText());
        }
        assertEquals(403,send("POST","/api/v1/auth/logout",Map.of(),noCsrf,null).statusCode());
        assertEquals(200,send("POST","/api/v1/auth/logout",Map.of(),a,null).statusCode());
        assertEquals(401,send("GET","/api/v1/privacy/preferences",null,a,null).statusCode());
    }
    @Test void exportRequiresThePasswordAndContainsOnlyOwnerDataAndDocuments()throws Exception {
        var a=session();var b=session();sql.update("UPDATE users SET name='Owner export marker' WHERE id=?",a.id());sql.update("UPDATE users SET name='Other private export marker',phone='+5561999999999' WHERE id=?",b.id());
        UUID ownFile=file(a.id(),"own-document-bytes"),otherFile=file(b.id(),"other-document-bytes");
        String details="Owner private rights request";send("POST","/api/v1/privacy/requests",Map.of("kind","access","details",details),a,UUID.randomUUID().toString());
        assertEquals(401,send("POST","/api/v1/privacy/export",Map.of("password","wrong password"),a,null).statusCode());
        assertEquals(422,send("POST","/api/v1/privacy/export",Map.of("password",PASSWORD,"userId",b.id().toString()),a,null).statusCode());
        long before;try(var files=Files.list(storagePath)){before=files.count();}
        var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/privacy/export")).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json").header("Cookie",a.cookie()).header("x-csrf-token",a.csrf()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("password",PASSWORD)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode(),new String(response.body()));assertEquals("application/zip",response.headers().firstValue("content-type").orElse(""));assertEquals("no-store",response.headers().firstValue("cache-control").orElse(""));
        var entries=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(response.body()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){entries.put(entry.getName(),zip.readAllBytes());}}
        assertTrue(entries.containsKey("documentos/"+ownFile+".png"));assertFalse(entries.containsKey("documentos/"+otherFile+".png"));assertArrayEquals("own-document-bytes".getBytes(),entries.get("documentos/"+ownFile+".png"));
        String data=new String(entries.get("dados.json"),java.nio.charset.StandardCharsets.UTF_8);assertTrue(data.contains("Owner export marker"));assertTrue(data.contains(details));assertFalse(data.contains("Other private export marker"));assertFalse(data.contains("+5561999999999"));assertFalse(data.contains("password_hash"));assertFalse(data.contains("csrf_hash"));assertFalse(data.contains("access_hash"));assertFalse(data.contains("storage_key"));assertFalse(data.contains(passwordHash));assertFalse(data.contains(a.csrf()));
        assertEquals("unicarona-personal-data-v1",json.readTree(data).path("format").asText());
        try(var files=Files.list(storagePath)){assertEquals(before,files.count());}
    }
    private UUID file(UUID owner,String content){
        UUID id=UUID.randomUUID();try{app.getBean(PrivateFileStorage.class).put(id.toString(),content.getBytes());}catch(IOException error){throw new UncheckedIOException(error);}
        sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,'identity',?,'image/png',?,?)",id,owner,id.toString(),content.length(),"c".repeat(64));return id;
    }
    @Test void closureRequiresPasswordExplicitAcknowledgmentAndCurrentPolicyAndCannotTargetAnotherAccount()throws Exception {
        var a=session();var b=session();var state=tree(send("GET","/api/v1/privacy/closure",null,a,null));String version=state.path("policyVersion").asText();
        assertEquals("none",state.path("status").asText());
        var body=new LinkedHashMap<String,Object>();body.put("password",PASSWORD);body.put("policyVersion",version);body.put("acknowledged",true);
        var wrong=new LinkedHashMap<>(body);wrong.put("password","wrong password");assertEquals(401,send("POST","/api/v1/privacy/closure",wrong,a,UUID.randomUUID().toString()).statusCode());
        wrong=new LinkedHashMap<>(body);wrong.put("acknowledged",false);assertEquals(422,send("POST","/api/v1/privacy/closure",wrong,a,UUID.randomUUID().toString()).statusCode());
        wrong=new LinkedHashMap<>(body);wrong.put("policyVersion","obsolete");assertEquals(409,send("POST","/api/v1/privacy/closure",wrong,a,UUID.randomUUID().toString()).statusCode());
        wrong=new LinkedHashMap<>(body);wrong.put("userId",b.id().toString());assertEquals(422,send("POST","/api/v1/privacy/closure",wrong,a,UUID.randomUUID().toString()).statusCode());
        var noCsrf=new Session(a.id(),a.cookie(),"wrong");assertEquals(403,send("POST","/api/v1/privacy/closure",body,noCsrf,UUID.randomUUID().toString()).statusCode());
        String key=UUID.randomUUID().toString();var created=send("POST","/api/v1/privacy/closure",body,a,key);assertEquals(202,created.statusCode(),created.body());assertEquals("scheduled",tree(created).path("status").asText());assertEquals(created.body(),send("POST","/api/v1/privacy/closure",body,a,key).body());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_account_closures WHERE user_id=?",Integer.class,a.id()));assertEquals("closing",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,b.id()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM users WHERE id=? AND deleted_at IS NOT NULL",Integer.class,a.id()));
        assertEquals(403,send("GET","/api/v1/rides",null,a,null).statusCode());assertEquals(200,send("GET","/api/v1/privacy/requests",null,a,null).statusCode());
    }
    @Test void cancellationNeedsReauthenticationDoesNotUndoSuspensionOrRestoreMarketing()throws Exception {
        var a=session();String version=tree(send("GET","/api/v1/privacy/closure",null,a,null)).path("policyVersion").asText();
        send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",true,"statementVersion",com.unicarona.privacy.service.PrivacyRightsService.MARKETING_VERSION),a,null);
        sql.update("UPDATE users SET account_status='suspended' WHERE id=?",a.id());
        assertEquals(202,send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),a,UUID.randomUUID().toString()).statusCode());assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));
        assertEquals(401,send("POST","/api/v1/privacy/closure/cancel",Map.of("password","wrong password"),a,UUID.randomUUID().toString()).statusCode());
        String key=UUID.randomUUID().toString();var cancelled=send("POST","/api/v1/privacy/closure/cancel",Map.of("password",PASSWORD),a,key);assertEquals(200,cancelled.statusCode(),cancelled.body());assertEquals(cancelled.body(),send("POST","/api/v1/privacy/closure/cancel",Map.of("password",PASSWORD),a,key).body());
        assertEquals("cancelled",tree(cancelled).path("status").asText());assertEquals("suspended",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,a.id()));assertFalse(tree(send("GET","/api/v1/privacy/preferences",null,a,null)).path("marketing").asBoolean());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=? AND NOT granted AND source='account_closure'",Integer.class,a.id()));
    }
    private UUID closeDue(Session user)throws Exception {
        String version=tree(send("GET","/api/v1/privacy/closure",null,user,null)).path("policyVersion").asText();var response=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",version),user,UUID.randomUUID().toString());assertEquals(202,response.statusCode(),response.body());UUID id=UUID.fromString(tree(response).path("id").asText());
        sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',reauthenticated_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE id=?",id);return id;
    }
    @Test void dueErasureRemovesProfileCredentialsAndPhysicalFileAndSignsAnIndependentJournal()throws Exception {
        var a=session();var other=session();UUID document=file(a.id(),"Private erase proof"),peer=file(other.id(),"Peer erase proof");UUID closure=closeDue(a);
        var maintenance=app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class);maintenance.run(20,()->false);maintenance.run(20,()->false);
        assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));var user=sql.queryForMap("SELECT name,email,phone,birth_date,password_hash,deleted_at FROM users WHERE id=?",a.id());
        assertEquals("Conta excluída",user.get("name"));assertTrue(((String)user.get("email")).endsWith("@deleted.invalid"));assertNull(user.get("phone"));assertNull(user.get("birth_date"));assertEquals("disabled",user.get("password_hash"));assertNotNull(user.get("deleted_at"));
        assertFalse(Files.exists(storagePath.resolve(document.toString())));assertTrue(Files.exists(storagePath.resolve(peer.toString())));assertNotNull(sql.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,document));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Integer.class,a.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_erasure_events WHERE closure_id=?",Integer.class,closure));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=?",Integer.class,a.id()));
        assertEquals(401,send("GET","/api/v1/privacy/preferences",null,a,null).statusCode());assertEquals(401,send("POST","/api/v1/privacy/export",Map.of("password",PASSWORD),a,null).statusCode());assertEquals(404,send("GET","/api/v1/files/"+document,null,other,null).statusCode());
        var entries=app.getBean(com.unicarona.privacy.service.PrivacyErasureJournal.class).entries();assertTrue(entries.stream().anyMatch(e->e.closure().equals(closure)&&e.owner().equals(a.id())));String journal=Files.readString(journalPath.resolve(closure.toString()));assertFalse(journal.contains("Synthetic privacy user"));assertFalse(journal.contains(PASSWORD));
    }
    @Test void aPhysicalDeletionFailureDoesNotClaimCompletionAndCanBeRetried()throws Exception {
        var a=session();UUID document=file(a.id(),"Retry erase proof");Path location=storagePath.resolve(document.toString());Files.delete(location);Files.createDirectory(location);Files.writeString(location.resolve("obstruction"),"Synthetic filesystem obstruction");UUID closure=closeDue(a);
        var maintenance=app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class);maintenance.run(20,()->false);assertEquals("erasing",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertNull(sql.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,document));assertEquals("FILE_ERASURE_RETRY",sql.queryForObject("SELECT last_error_code FROM privacy_erasure_tasks WHERE closure_id=?",String.class,closure));
        Files.delete(location.resolve("obstruction"));Files.delete(location);sql.update("UPDATE stored_files SET erasure_retry_after=now() WHERE id=?",document);maintenance.run(20,()->false);assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));
    }
    @Test void aFiniteJustifiedFileHoldPreventsPhysicalErasureUntilItExpires()throws Exception {
        var a=session();var operator=session();UUID document=file(a.id(),"Evidence retained under a finite hold");
        String reason=app.getBean(CryptoService.class).encrypt("Synthetic specified case requiring the document",AuthTestSettings.KEY);
        UUID hold=sql.queryForObject("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'files','stored_file',?,'regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days') RETURNING id",UUID.class,a.id(),document,reason,operator.id());
        UUID closure=closeDue(a);var maintenance=app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class);maintenance.run(20,()->false);
        assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertTrue(Files.exists(storagePath.resolve(document.toString())));assertNull(sql.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,document));assertEquals(404,send("GET","/api/v1/files/"+document,null,operator,null).statusCode());
        sql.update("UPDATE privacy_legal_holds SET created_at=now()-interval '3 days',review_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE id=?",hold);maintenance.run(20,()->false);assertFalse(Files.exists(storagePath.resolve(document.toString())));assertNotNull(sql.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,document));
    }
    @Test void replayingTheIndependentJournalSuppressesAProfileAndPhotoRestoredFromAnOlderSnapshot()throws Exception {
        var a=session();var other=session();UUID document=file(a.id(),"Restored private file bytes");var original=sql.queryForMap("SELECT name,email,password_hash FROM users WHERE id=?",a.id());var originalSession=sql.queryForMap("SELECT * FROM sessions WHERE user_id=? AND revoked_at IS NULL",a.id());UUID closure=closeDue(a);
        app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class).run(20,()->false);
        assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));
        // Restore pre-erasure rows and bytes in the disposable test DB, keeping the independent journal intact.
        sql.update("DELETE FROM privacy_erasure_events WHERE closure_id=?",closure);sql.update("DELETE FROM privacy_erasure_tasks WHERE closure_id=?",closure);sql.update("DELETE FROM privacy_account_closures WHERE id=?",closure);
        sql.update("UPDATE users SET name=?,email=?,password_hash=?,birth_date='2000-01-01',deleted_at=NULL,account_status='active' WHERE id=?",original.get("name"),original.get("email"),original.get("password_hash"),a.id());
        sql.update("INSERT INTO sessions(id,user_id,access_hash,refresh_hash,csrf_hash,family_id,access_expires_at,refresh_expires_at) VALUES(?,?,?,?,?,?,?,?)",originalSession.get("id"),a.id(),originalSession.get("access_hash"),originalSession.get("refresh_hash"),originalSession.get("csrf_hash"),originalSession.get("family_id"),originalSession.get("access_expires_at"),originalSession.get("refresh_expires_at"));
        sql.update("UPDATE stored_files SET deleted_at=NULL,erasure_requested_at=NULL,erased_at=NULL WHERE id=?",document);app.getBean(PrivateFileStorage.class).put(document.toString(),"Restored private file bytes".getBytes());
        var guard=app.getBean(com.unicarona.privacy.service.PrivacyRestoreGuard.class);guard.run(new org.springframework.boot.DefaultApplicationArguments());guard.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,a.id()));assertFalse(Files.exists(storagePath.resolve(document.toString())));assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,other.id()));assertEquals(401,send("GET","/api/v1/users/me",null,a,null).statusCode());assertEquals(404,send("GET","/api/v1/files/"+document,null,other,null).statusCode());assertTrue(app.getBean(com.unicarona.privacy.service.PrivacyReadiness.class).ready());
    }
    @Test void changingTheJournalIsDetectedAndReadinessBlocksApisUntilRecoveryCompletes()throws Exception {
        var a=session();var journal=app.getBean(com.unicarona.privacy.service.PrivacyErasureJournal.class);UUID closure=UUID.randomUUID();var at=java.time.Instant.now();journal.ensure(a.id(),closure,at,at.plusSeconds(86400));Path file=journalPath.resolve(closure.toString());String valid=Files.readString(file);
        try{Files.writeString(file,valid.replace(a.id().toString(),UUID.randomUUID().toString()));assertThrows(IOException.class,journal::entries);}finally{Files.writeString(file,valid);Files.delete(file);}
        var ready=app.getBean(com.unicarona.privacy.service.PrivacyReadiness.class);ready.begin();try{assertEquals(503,send("GET","/api/v1/users/me",null,a,null).statusCode());assertEquals(503,http.send(HttpRequest.newBuilder(URI.create(server+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());}finally{ready.complete();}
        assertEquals(200,send("GET","/api/v1/users/me",null,a,null).statusCode());
    }
    @Test void expiredEncryptedEvidenceIsPurgedButAnActiveJustifiedHoldWinsUntilExpiry()throws Exception {
        var a=session();var b=session();String cipher=app.getBean(CryptoService.class).encrypt("Synthetic restricted evidence",AuthTestSettings.KEY);
        for(UUID owner:List.of(a.id(),b.id()))sql.update("INSERT INTO privacy_retained_records(user_id,category,content_ciphertext,reason,legal_basis,created_at,delete_after) VALUES(?,'contract_identity',?,'Synthetic justified expiry','regular_exercise_of_rights',now()-interval '6 years',now()-interval '1 day')",owner,cipher);
        UUID hold=sql.queryForObject("INSERT INTO privacy_legal_holds(user_id,category,resource_type,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'contract_identity','user','regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days') RETURNING id",UUID.class,a.id(),cipher,b.id());
        var retention=app.getBean(com.unicarona.privacy.service.PrivacyRetentionTransactions.class);retention.prune(100);
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=?",Integer.class,a.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=?",Integer.class,b.id()));
        sql.update("UPDATE privacy_legal_holds SET created_at=now()-interval '3 days',review_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE id=?",hold);retention.prune(100);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=?",Integer.class,a.id()));
    }
    @Test void aSpecificProfileHoldKeepsOnlyAnEncryptedCopyWhileTheVisibleProfileIsErased()throws Exception {
        var a=session();var operator=session();String reason=app.getBean(CryptoService.class).encrypt("Synthetic specific profile hold",AuthTestSettings.KEY);
        sql.update("INSERT INTO privacy_legal_holds(user_id,category,resource_type,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'profile','user','regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",a.id(),reason,operator.id());
        closeDue(a);app.getBean(com.unicarona.privacy.service.PrivacyErasureMaintenance.class).run(20,()->false);assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,a.id()));assertNull(sql.queryForObject("SELECT birth_date FROM users WHERE id=?",java.sql.Date.class,a.id()));
        var retained=sql.queryForMap("SELECT id,content_ciphertext FROM privacy_retained_records WHERE user_id=? AND category='profile'",a.id());String evidence=(String)retained.get("content_ciphertext");assertFalse(evidence.contains("Synthetic privacy user"));assertTrue(app.getBean(com.unicarona.privacy.service.PrivacyEvidenceCrypto.class).open(com.unicarona.privacy.service.PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,a.id(),(UUID)retained.get("id"),evidence).contains("Synthetic privacy user"));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND category='profile' AND delete_after<created_at+interval '3 days'",Integer.class,a.id()));
    }
    @Test void retentionClearsFinishedEmailRecipientsAndOldNotificationsButKeepsCurrentGrantedConsent()throws Exception {
        var a=session();var b=session();UUID job=sql.queryForObject("INSERT INTO outbox_jobs(type,event_key,payload,status,completed_at) VALUES('email',?,jsonb_build_object('to',?::text),'done',now()) RETURNING id",UUID.class,"synthetic-retention-"+UUID.randomUUID(),"synthetic-private@example.test");
        UUID notice=sql.queryForObject("INSERT INTO notifications(user_id,title,body,route,created_at) VALUES(?,'Synthetic old notice','Synthetic private notice','settings',now()-interval '91 days') RETURNING id",UUID.class,a.id());
        sql.update("INSERT INTO privacy_preferences(user_id,marketing,marketing_statement_version) VALUES(?,true,'synthetic-historical-version')",a.id());
        for(UUID owner:List.of(a.id(),b.id()))sql.update("INSERT INTO privacy_consent_events(user_id,purpose,granted,statement_version,source,created_at,retain_until) VALUES(?,'marketing',true,'synthetic-historical-version','privacy_center',now()-interval '6 years',now()-interval '1 day')",owner);
        app.getBean(com.unicarona.privacy.service.PrivacyRetentionTransactions.class).prune(100);
        assertEquals("{}",sql.queryForObject("SELECT payload::text FROM outbox_jobs WHERE id=?",String.class,job));assertEquals(0,sql.queryForObject("SELECT count(*) FROM notifications WHERE id=?",Integer.class,notice));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=?",Integer.class,a.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=?",Integer.class,b.id()));
        assertEquals(200,send("PATCH","/api/v1/privacy/preferences",Map.of("marketing",false),a,null).statusCode());assertEquals(2,sql.queryForObject("SELECT count(*) FROM privacy_consent_events WHERE user_id=? AND retain_until>now()+interval '4 years'",Integer.class,a.id()));
    }
    @Test void onlyAnAdministratorCanRespondAndTheOwnerReceivesAnEncryptedAuditedAnswer()throws Exception {
        var a=session();var operator=session();sql.update("UPDATE users SET role='admin' WHERE id=?",operator.id());
        String id=tree(send("POST","/api/v1/privacy/requests",Map.of("kind","correction","details","Synthetic correction request"),a,UUID.randomUUID().toString())).path("id").asText();
        String path="/api/v1/admin/privacy/requests/"+id;assertEquals(403,send("GET",path,null,a,null).statusCode());assertEquals(200,send("GET",path,null,operator,null).statusCode());
        String message="Synthetic correction performed and described to the account owner.";var body=Map.of("expectedStatus","received","status","completed","message",message);
        assertEquals(409,send("POST",path+"/respond",body,operator,UUID.randomUUID().toString()).statusCode());
        var confirmed=new LinkedHashMap<String,Object>(body);confirmed.put("actionPerformed",true);String key=UUID.randomUUID().toString();var result=send("POST",path+"/respond",confirmed,operator,key);assertEquals(200,result.statusCode(),result.body());assertEquals(result.body(),send("POST",path+"/respond",confirmed,operator,key).body());
        assertFalse(sql.queryForObject("SELECT body_ciphertext FROM privacy_request_responses WHERE request_id=?::uuid",String.class,id).contains(message));
        var owner=tree(send("GET","/api/v1/privacy/requests/"+id,null,a,null));assertEquals("completed",owner.path("request").path("status").asText());assertEquals(message,owner.path("responses").get(0).path("message").asText());
        assertEquals(409,send("POST",path+"/respond",confirmed,operator,UUID.randomUUID().toString()).statusCode());
    }
}
