package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Real old/new archives around journal expiry. Only the dedicated disposable PostgreSQL database is reset. */
class PrivacyRestoreFenceIT {
    private static RealPostgres database;
    @TempDir Path temporary;
    private ServletWebServerApplicationContext app;private JdbcTemplate sql;private String[] args;private Path operations,storage,journalRoot;
    @BeforeAll static void database(){database=new RealPostgres();}
    @AfterAll static void closeDatabase(){if(database!=null)database.close();}
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    @BeforeEach void initialize()throws Exception {
        database.resetSchema();operations=temporary.resolve("ops");storage=temporary.resolve("live-files");journalRoot=temporary.resolve("independent-journal");
        var values=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));values.addAll(List.of("--PRIVACY_OPERATIONS_ROOT="+operations,"--STORAGE_PATH="+storage,"--DEV_MAIL_PATH="+temporary.resolve("mail"),"--PRIVACY_ERASURE_JOURNAL_PATH="+journalRoot));args=values.toArray(String[]::new);
        var env=new MockEnvironment();for(String arg:args){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}
        var json=new ObjectMapper().registerModule(new JavaTimeModule());var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);
        new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,Clock.systemUTC()).initialize();
        start();
    }
    private void start(){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args);sql=app.getBean(JdbcTemplate.class);}
    private UUID user(String name){return sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,'not-an-auth-fixture',?,'2000-01-01',now()) RETURNING id",UUID.class,UUID.randomUUID()+"@example.test",name);}
    private UUID file(UUID owner)throws Exception {UUID id=UUID.randomUUID();Files.createDirectories(storage);Files.writeString(storage.resolve(id.toString()),"Synthetic private bytes");sql.update("INSERT INTO stored_files(id,owner_id,purpose,storage_key,content_type,byte_size,sha256) VALUES(?,?,'identity',?,'image/png',23,?)",id,owner,id.toString(),"a".repeat(64));return id;}
    private HttpResponse<String> ready()throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+"/health/ready")).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private void interruptedHoldCommit(UUID owner){
        assertThrows(RuntimeException.class,()->app.getBean(TransactionTemplate.class).executeWithoutResult(status->{
            UUID hold=UUID.randomUUID();String reason=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.HOLD_REASON,owner,hold,"Synthetic commit-gap test reason");
            sql.update("INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,'profile','user',?,'regular_exercise_of_rights',?,?,now()+interval '1 hour',now()+interval '1 day')",hold,owner,owner,reason,owner);
            app.getBean(PrivacyHoldRecovery.class).stage();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MIN_VALUE;}@Override public void afterCommit(){throw new IllegalStateException("Synthetic post-commit interruption");}});
        }));
    }
    @Test void actualApplicationRestartConfirmsTheCommittedMarkerBeforeOpeningPrivacyWork()throws Exception {
        UUID owner=user("Synthetic held owner");interruptedHoldCommit(owner);assertEquals(503,ready().statusCode());assertTrue(app.getBean(PrivacyHoldControlTransactions.class).marker().pending());assertTrue(app.getBean(PrivacyHoldControlJournal.class).pending().isPresent());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE user_id=?",Integer.class,owner));
        app.close();app=null;start();assertEquals(200,ready().statusCode());assertFalse(app.getBean(PrivacyHoldControlTransactions.class).marker().pending());assertTrue(app.getBean(PrivacyHoldControlJournal.class).pending().isEmpty());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE user_id=?",Integer.class,owner));
    }
    @Test void anUnprovenPreparationSurvivesRestoringAnOlderArchiveAndBlocksStartup()throws Exception {
        UUID owner=user("Synthetic uncertain outcome owner");Path before=temporary.resolve("before-hold.dump");database.dumpDatabaseArchive(before);interruptedHoldCommit(owner);app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(before);
        Exception rejected=assertThrows(Exception.class,this::start);assertTrue(chain(rejected).contains("Não foi possível confirmar o controle de preservação"));if(app!=null){app.close();app=null;}
        try(var records=Files.list(operations.resolve("hold-control"))){assertTrue(records.anyMatch(path->path.getFileName().toString().endsWith(".json")),"Recovery must retain the uncertain independent evidence");}
    }
    @Test void anArchiveOlderThanExpiredSuppressionFailsBootWhileANewerArchiveAndRecordedFileSuppressionRecover()throws Exception {
        UUID owner=user("Synthetic deleted profile"),peer=user("Synthetic preserved peer"),document=file(owner),peerFile=file(peer),closure=UUID.randomUUID();
        // Deliberately establish an older fixture watermark before taking the physical old snapshot.
        UUID dataset=sql.queryForObject("SELECT dataset_id FROM privacy_restore_state",UUID.class);sql.execute("TRUNCATE privacy_restore_state");sql.update("INSERT INTO privacy_restore_state(singleton,dataset_id,observed_at) VALUES(true,?,now()-interval '40 days')",dataset);
        var catalog=app.getBean(PrivacyBackupCatalog.class);String name=UUID.randomUUID().toString();Path copy=Files.createDirectories(operations.resolve("backups").resolve(name));Path old=copy.resolve("database.dump");database.dumpDatabaseArchive(old);
        Path forbidden=temporary.resolve("unmanaged-old-archive.dump");Files.copy(old,forbidden); // Simulates a manual restore bypassing the expiry-aware backup protocol.
        catalog.locked(()->catalog.register("backups",name,Instant.now().minus(Duration.ofDays(40)),Instant.EPOCH,true));
        var journal=app.getBean(PrivacyErasureJournal.class);Instant prepared=Instant.now().minus(Duration.ofDays(38));journal.ensure(owner,closure,prepared,prepared.plus(Duration.ofDays(37)));
        app.getBean(PrivacyErasureMaintenance.class).replay(journal.entries().getFirst());assertFalse(Files.exists(storage.resolve(document.toString())));
        app.getBean(PrivacyRestoreFence.class).markObserved();var result=app.getBean(PrivacyCopiesMaintenance.class).runOnce(100);
        assertEquals(1,result.expiredJournalEntries());assertFalse(Files.exists(old));assertTrue(journal.entries().isEmpty());assertNull(catalog.store().pending());assertTrue(catalog.store().minimumRestorePointEpochMs()>=prepared.toEpochMilli());
        Path current=temporary.resolve("current-archive.dump");database.dumpDatabaseArchive(current);
        app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(forbidden);Files.writeString(storage.resolve(document.toString()),"Restored old private bytes");
        Exception rejected=assertThrows(Exception.class,this::start);assertTrue(chain(rejected).contains("independent privacy recovery checkpoint"));assertNull(app);
        database.resetSchema();database.restoreDatabaseArchive(current);start();assertEquals(200,ready().statusCode());assertEquals("deleted",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,owner));
        assertFalse(Files.exists(storage.resolve(document.toString())));assertTrue(Files.exists(storage.resolve(peerFile.toString())));assertEquals("Synthetic preserved peer",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,peer));
        assertTrue(app.getBean(PrivacyErasureJournal.class).entries().isEmpty());
    }
    @Test void newUnregisteredCopiesPauseExpiryWithoutStarvingOtherMaintenanceAndMissingEvidenceBlocksReadiness()throws Exception {
        var catalog=app.getBean(PrivacyBackupCatalog.class);var journal=app.getBean(PrivacyErasureJournal.class);UUID owner=user("Synthetic owner"),closure=UUID.randomUUID();Instant at=Instant.now().minus(Duration.ofDays(38));journal.ensure(owner,closure,at,at.plus(Duration.ofDays(37)));
        Path unknown=Files.createDirectory(operations.resolve("archives/unregistered"));Files.writeString(unknown.resolve("proof"),"Synthetic earlier copy");
        assertThrows(java.io.IOException.class,()->app.getBean(PrivacyCopiesMaintenance.class).runOnce(100));assertDoesNotThrow(()->app.getBean(PrivacyCopiesMaintenance.class).run(100,()->false));assertEquals(1,journal.entries().size());
        Path recorded=journalRoot.resolve(closure.toString());byte[] saved=Files.readAllBytes(recorded);Files.delete(recorded);
        var guard=app.getBean(PrivacyRestoreGuard.class);assertThrows(java.io.IOException.class,()->guard.run(new org.springframework.boot.DefaultApplicationArguments()));assertEquals(503,ready().statusCode());assertFalse(app.getBean(PrivacyReadiness.class).ready());
        Files.write(recorded,saved);catalog.locked(()->catalog.register("archives","unregistered",Instant.now(),Instant.EPOCH,true));guard.run(new org.springframework.boot.DefaultApplicationArguments());assertEquals(200,ready().statusCode());
    }
    private String chain(Throwable error){var result=new StringBuilder();for(Throwable item=error;item!=null;item=item.getCause())result.append(item.getMessage()).append('\n');return result.toString();}
    @Test void anotherDatabaseCannotShareTheSuppressionStoreEvenBeforeTheFirstExpiry()throws Exception {
        UUID closure=UUID.randomUUID();Instant at=Instant.now();app.getBean(PrivacyErasureJournal.class).ensure(user("Original dataset fixture"),closure,at,at.plus(Duration.ofDays(37)));
        app.close();app=null;database.resetSchema();Exception wrong=assertThrows(Exception.class,this::start);assertTrue(chain(wrong).contains("different private recovery dataset"));assertNull(app);assertTrue(Files.exists(journalRoot.resolve(closure.toString())));
    }
}
