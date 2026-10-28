package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.unicarona.UniCaronaApplication;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real pg_dump/pg_restore plus restored private bytes. The independent journal is deliberately outside the backup. */
class PrivacyPhysicalRestoreIT {
    private RealPostgres db;private ServletWebServerApplicationContext app;private JdbcTemplate sql;
    private Path workspace,storage,journal;private String server,passwordHash;
    private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();
    private static final String PASSWORD="Synthetic physical restore password 2026";
    private record Session(UUID id,String email,String cookie,String csrf){}
    private void start(){
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(db.apiConnectionUrl())));
        args.addAll(List.of("--STORAGE_PATH="+storage,"--DEV_MAIL_PATH="+workspace.resolve("mail"),"--PRIVACY_ERASURE_JOURNAL_PATH="+journal));
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args.toArray(String[]::new));
        sql=app.getBean(JdbcTemplate.class);server="http://127.0.0.1:"+app.getWebServer().getPort();passwordHash=app.getBean(CryptoService.class).hashPassword(PASSWORD);
    }
    @AfterEach void cleanup()throws Exception {
        if(app!=null)app.close();if(db!=null)db.close();
        if(workspace!=null&&Files.exists(workspace)){
            Path anchor=workspace.toAbsolutePath().normalize();assertTrue(anchor.getFileName().toString().startsWith("unicarona-privacy-physical-"));
            List<Path> files;try(var walk=Files.walk(anchor)){files=walk.sorted(Comparator.reverseOrder()).toList();}
            for(Path path:files){assertTrue(path.toAbsolutePath().normalize().startsWith(anchor));Files.delete(path);}
        }
    }
    private Session session()throws Exception {
        String email=UUID.randomUUID()+"@example.test";UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'Synthetic restore user','2000-01-01',now()) RETURNING id",UUID.class,email,passwordHash);
        var response=send("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),null);assertEquals(200,response.statusCode());
        var cookies=new LinkedHashMap<String,String>();for(String header:response.headers().allValues("set-cookie")){var pair=header.split(";",2)[0].split("=",2);cookies.put(pair[0],pair[1]);}
        return new Session(id,email,String.join("; ",cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList()),cookies.get("uc_csrf"));
    }
    private HttpResponse<String> send(String method,String path,Object body,Session owner)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(server+path)).header("Origin",AuthTestSettings.ORIGIN).header("Content-Type","application/json");
        if(owner!=null)request.header("Cookie",owner.cookie()).header("x-csrf-token",owner.csrf());
        if(!method.equals("GET"))request.header("idempotency-key",UUID.randomUUID().toString());
        return http.send(request.method(method,method.equals("GET")?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private UUID file(UUID owner,String body)throws Exception {
        UUID id=UUID.randomUUID();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);app.getBean(PrivateFileStorage.class).put(id.toString(),bytes);
        sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,'identity',?,'image/png',?,?)",id,owner,id.toString(),bytes.length,"d".repeat(64));return id;
    }
    private String ledgerFingerprint(){return sql.queryForObject("SELECT md5(coalesce(string_agg(row_to_json(e)::text,'' ORDER BY e.id),'')) FROM ledger_entries e",String.class);}
    @Test void restoringAnActualDatabaseArchiveAndFilesCannotResurrectAnErasedProfileOrSession()throws Exception {
        db=new RealPostgres();db.resetSchema();workspace=Files.createTempDirectory("unicarona-privacy-physical-");storage=Files.createDirectory(workspace.resolve("live-files"));journal=Files.createDirectory(workspace.resolve("independent-journal"));start();
        var owner=session();var peer=session();UUID ownFile=file(owner.id(),"Synthetic owner private bytes"),peerFile=file(peer.id(),"Synthetic peer private bytes");
        app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{
            UUID clearing=sql.queryForObject("INSERT INTO ledger_accounts(kind) VALUES('provider_clearing') RETURNING id",UUID.class);
            UUID credit=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,'passenger_credit') RETURNING id",UUID.class,peer.id());
            UUID transaction=sql.queryForObject("INSERT INTO ledger_transactions(event_key,kind,reason,body_hash) VALUES(?,'synthetic_restore_proof','Synthetic balance preservation',?) RETURNING id",UUID.class,"restore-proof:"+UUID.randomUUID(),"a".repeat(64));
            sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents) VALUES(?,?,-2300),(?,?,2300)",transaction,clearing,transaction,credit);
        });
        String ledgerBefore=ledgerFingerprint();Path backup=Files.createDirectory(workspace.resolve("backup"));
        db.dumpDatabaseArchive(backup.resolve("database.dump"));
        assertTrue(Files.size(backup.resolve("database.dump"))>0);
        for(UUID file:List.of(ownFile,peerFile))Files.copy(storage.resolve(file.toString()),backup.resolve(file.toString()));
        assertFalse(Files.exists(backup.resolve("independent-journal")));
        var preview=json.readTree(send("GET","/api/v1/privacy/closure",null,owner).body());
        var requested=send("POST","/api/v1/privacy/closure",Map.of("password",PASSWORD,"acknowledged",true,"policyVersion",preview.path("policyVersion").asText()),owner);assertEquals(202,requested.statusCode());
        UUID closure=UUID.fromString(json.readTree(requested.body()).path("id").asText());
        sql.update("UPDATE privacy_account_closures SET requested_at=now()-interval '8 days',reauthenticated_at=now()-interval '8 days',erase_after=now()-interval '1 day' WHERE id=?",closure);
        app.getBean(PrivacyErasureMaintenance.class).run(20,()->false);
        assertEquals("completed",sql.queryForObject("SELECT status FROM privacy_account_closures WHERE id=?",String.class,closure));assertFalse(Files.exists(storage.resolve(ownFile.toString())));assertTrue(Files.exists(journal.resolve(closure.toString())));
        app.close();app=null;
        db.resetSchema();db.restoreDatabaseArchive(backup.resolve("database.dump"));
        Files.copy(backup.resolve(ownFile.toString()),storage.resolve(ownFile.toString()),StandardCopyOption.REPLACE_EXISTING);
        // Demonstrate that this is the older physical snapshot before booting the guarded application.
        var direct=new JdbcTemplate(db.dataSource());assertEquals(owner.email(),direct.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner.id()));
        assertEquals("Acao",direct.queryForObject("SELECT public.unaccent('Ação')",String.class));
        assertEquals("active",direct.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner.id()));assertEquals(1,direct.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Integer.class,owner.id()));assertTrue(Files.exists(storage.resolve(ownFile.toString())));
        assertEquals(0,direct.queryForObject("SELECT count(*) FROM privacy_account_closures WHERE id=?",Integer.class,closure));
        start();assertTrue(app.getBean(PrivacyReadiness.class).ready());
        assertEquals(200,send("GET","/health/ready",null,null).statusCode());assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,owner.id()));assertEquals("deleted",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner.id()));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM sessions WHERE user_id=?",Integer.class,owner.id()));assertEquals(401,send("GET","/api/v1/users/me",null,owner).statusCode());assertFalse(Files.exists(storage.resolve(ownFile.toString())));assertEquals(404,send("GET","/api/v1/files/"+ownFile,null,peer).statusCode());
        assertEquals(200,send("GET","/api/v1/users/me",null,peer).statusCode());assertEquals(peer.email(),sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,peer.id()));assertTrue(Files.exists(storage.resolve(peerFile.toString())));assertEquals(ledgerBefore,ledgerFingerprint());assertEquals(0L,sql.queryForObject("SELECT sum(cents) FROM ledger_entries",Long.class));
        // A file-only restore after the profile was already suppressed is also caught, twice without side effects.
        Files.copy(backup.resolve(ownFile.toString()),storage.resolve(ownFile.toString()),StandardCopyOption.REPLACE_EXISTING);
        var guard=app.getBean(PrivacyRestoreGuard.class);guard.run(new org.springframework.boot.DefaultApplicationArguments());guard.run(new org.springframework.boot.DefaultApplicationArguments());
        assertFalse(Files.exists(storage.resolve(ownFile.toString())));assertTrue(Files.exists(storage.resolve(peerFile.toString())));assertEquals(ledgerBefore,ledgerFingerprint());
    }
    @Test void restoringAnActualArchiveRemovesUnusedPreciseLocationButKeepsTheArchivedSpecificHold()throws Exception {
        db=new RealPostgres();db.resetSchema();workspace=Files.createTempDirectory("unicarona-privacy-physical-");storage=Files.createDirectory(workspace.resolve("live-files"));journal=Files.createDirectory(workspace.resolve("independent-journal"));start();
        var owner=session();UUID university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES('synthetic-location-restore','Synthetic campus','Synthetic city','GO') RETURNING id",UUID.class);
        var routes=new ArrayList<UUID>();for(int n=0;n<2;n++)routes.add(sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m,origin_address,destination_address,origin_lat,origin_lng,destination_lat,destination_lng) VALUES(?,?,'Synthetic region','Synthetic meeting',12000,'Synthetic private origin','Synthetic private destination',-16.1,-48.1,-16.2,-48.2) RETURNING id",UUID.class,university,owner.id()));
        UUID ordinary=routes.get(0),held=routes.get(1);sql.update("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'location','ride_route',?,'regular_exercise_of_rights','Synthetic archived specific hold',?,now()+interval '1 hour',now()+interval '1 day')",owner.id(),held,owner.id());
        app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{
            UUID clearing=sql.queryForObject("INSERT INTO ledger_accounts(kind) VALUES('provider_clearing') RETURNING id",UUID.class),credit=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,'passenger_credit') RETURNING id",UUID.class,owner.id());
            UUID transaction=sql.queryForObject("INSERT INTO ledger_transactions(event_key,kind,reason,body_hash) VALUES(?,'synthetic_restore_proof','Synthetic location cleanup balance preservation',?) RETURNING id",UUID.class,"location-restore-proof:"+UUID.randomUUID(),"b".repeat(64));
            sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents) VALUES(?,?,-700),(?,?,700)",transaction,clearing,transaction,credit);
        });assertEquals(2,sql.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));
        Path backup=Files.createDirectory(workspace.resolve("backup")).resolve("database.dump");db.dumpDatabaseArchive(backup);String ledgerBefore=ledgerFingerprint();app.getBean(PrivacyLocationRetentionTransactions.class).suppressBeforeReadiness();assertNull(sql.queryForObject("SELECT origin_address FROM ride_routes WHERE id=?",String.class,ordinary));
        app.close();app=null;db.resetSchema();db.restoreDatabaseArchive(backup);var direct=new JdbcTemplate(db.dataSource());assertEquals("Synthetic private origin",direct.queryForObject("SELECT origin_address FROM ride_routes WHERE id=?",String.class,ordinary));
        start();assertEquals(200,send("GET","/health/ready",null,null).statusCode());assertEquals(200,send("GET","/api/v1/auth/me",null,owner).statusCode());
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ride_routes WHERE id=? AND (origin_address IS NOT NULL OR destination_address IS NOT NULL OR origin_lat IS NOT NULL OR origin_lng IS NOT NULL OR destination_lat IS NOT NULL OR destination_lng IS NOT NULL)",Integer.class,ordinary));
        assertEquals("Synthetic private origin",sql.queryForObject("SELECT origin_address FROM ride_routes WHERE id=?",String.class,held));assertEquals("Synthetic meeting",sql.queryForObject("SELECT meeting FROM ride_routes WHERE id=?",String.class,ordinary));assertEquals(ledgerBefore,ledgerFingerprint());
        assertEquals(700L,sql.queryForObject("SELECT sum(e.cents) FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id WHERE a.owner_id=? AND a.kind='passenger_credit'",Long.class,owner.id()));
    }
    @Test void restoringARealSnapshotWithTheOldCurrentPhotoCannotResurrectAnIndividuallyErasedUpload()throws Exception {
        db=new RealPostgres();db.resetSchema();workspace=Files.createTempDirectory("unicarona-privacy-physical-");storage=Files.createDirectory(workspace.resolve("live-files"));journal=Files.createDirectory(workspace.resolve("independent-journal"));start();
        var owner=session();UUID old=file(owner.id(),"Synthetic old profile photo bytes");sql.update("UPDATE stored_files SET purpose='photo',created_at=now()-interval '2 days',last_uploaded_at=now()-interval '2 days' WHERE id=?",old);sql.update("UPDATE users SET photo_file_id=? WHERE id=?",old,owner.id());
        Path backup=Files.createDirectory(workspace.resolve("backup"));db.dumpDatabaseArchive(backup.resolve("database.dump"));Files.copy(storage.resolve(old.toString()),backup.resolve(old.toString()));String ledgerBefore=ledgerFingerprint();
        UUID current=file(owner.id(),"Synthetic new photo bytes preserved before restore");sql.update("UPDATE stored_files SET purpose='photo' WHERE id=?",current);sql.update("UPDATE users SET photo_file_id=? WHERE id=?",current,owner.id());
        assertTrue(app.getBean(PrivacyUnusedFiles.class).retire(new PrivacyUnusedFiles.Candidate(old,owner.id())));assertTrue(app.getBean(PrivacyFileErasure.class).erase(old));assertFalse(Files.exists(storage.resolve(old.toString())));assertTrue(Files.exists(journal.resolve("file-"+old)));assertTrue(app.getBean(PrivacyErasureJournal.class).entries().isEmpty());
        app.close();app=null;db.resetSchema();db.restoreDatabaseArchive(backup.resolve("database.dump"));Files.copy(backup.resolve(old.toString()),storage.resolve(old.toString()),StandardCopyOption.REPLACE_EXISTING);
        var direct=new JdbcTemplate(db.dataSource());assertEquals(old,direct.queryForObject("SELECT photo_file_id FROM users WHERE id=?",UUID.class,owner.id()));assertNull(direct.queryForObject("SELECT erased_at FROM stored_files WHERE id=?",java.sql.Timestamp.class,old));assertTrue(Files.exists(storage.resolve(old.toString())));
        start();assertEquals(200,send("GET","/health/ready",null,null).statusCode());assertFalse(Files.exists(storage.resolve(old.toString())));assertNull(sql.queryForObject("SELECT photo_file_id FROM users WHERE id=?",UUID.class,owner.id()));assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner.id()));assertEquals(owner.email(),sql.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner.id()));assertEquals(200,send("GET","/api/v1/auth/me",null,owner).statusCode());assertEquals(404,send("GET","/api/v1/files/"+old,null,owner).statusCode());assertEquals(ledgerBefore,ledgerFingerprint());assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_account_closures WHERE user_id=?",Integer.class,owner.id()));
        Files.copy(backup.resolve(old.toString()),storage.resolve(old.toString()),StandardCopyOption.REPLACE_EXISTING);app.getBean(PrivacyRestoreGuard.class).run(new org.springframework.boot.DefaultApplicationArguments());assertFalse(Files.exists(storage.resolve(old.toString())));
    }
}
