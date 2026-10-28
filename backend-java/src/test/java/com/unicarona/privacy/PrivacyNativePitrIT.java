package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.payment.ledger.AccountKind;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.recovery.*;
import com.unicarona.privacy.service.*;
import com.unicarona.reservation.service.LifecycleTransactions;
import com.unicarona.security.*;
import com.unicarona.support.*;
import com.unicarona.support.PaidReservationFixture.Paid;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real pg_basebackup + encrypted WAL + native replay. Fresh synthetic clusters, never the local application database. */
class PrivacyNativePitrIT {
    public static class PrivateTemp implements org.junit.jupiter.api.io.TempDirFactory {
        @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,org.junit.jupiter.api.extension.ExtensionContext context)throws java.io.IOException {return Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"pitr-");}
    }
    @TempDir(factory=PrivateTemp.class) Path temporary;private ServletWebServerApplicationContext app;private JdbcTemplate sql;
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    private String[] arguments(IsolatedPitrPostgres pg,boolean recovery,Path root){var values=new ArrayList<>(List.of(AuthTestSettings.arguments(pg.url(recovery))));values.addAll(List.of("--STORAGE_PATH="+root.resolve("files"),"--FRONTEND_PATH="+root.resolve("public"),"--PRIVACY_OPERATIONS_ROOT="+root.resolve("ops"),"--PRIVACY_ERASURE_JOURNAL_PATH="+root.resolve("journal"),"--DEV_MAIL_PATH="+root.resolve("mail")));return values.toArray(String[]::new);}
    private PrivacyBackupCatalog catalog(String[] arguments,Clock clock)throws Exception {var env=new MockEnvironment();for(String arg:arguments){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);return new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,clock);}
    private void start(String[] arguments){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(arguments);sql=app.getBean(JdbcTemplate.class);}
    private void stop(){if(app!=null){app.close();app=null;}}
    private Actor actor(UUID id){return new Actor(id,"user","active",UUID.randomUUID(),UUID.randomUUID(),"synthetic",true,Set.of("ROLE_USER"));}
    private List<String> money(){return sql.queryForList("SELECT 'payments:'||to_jsonb(p)::text FROM payments p UNION ALL SELECT 'parcels:'||to_jsonb(p)::text FROM parcels p UNION ALL SELECT 'journals:'||to_jsonb(j)::text FROM ledger_transactions j UNION ALL SELECT 'entries:'||to_jsonb(e)::text FROM ledger_entries e ORDER BY 1",String.class);}
    private void assertConsumed(Paid paid){assertTrue(Boolean.TRUE.equals(sql.queryForObject("SELECT pin_ciphertext IS NULL AND pin_hash IS NULL AND pin_erased_at IS NOT NULL FROM ride_reservations WHERE id=?",Boolean.class,paid.id())));}
    @Test void nativeReplayRecoversPostBaseBoardingAndCreditThenReappliesNewerPrivacyControlsAndRefusesBrokenHistory()throws Exception {
        Path root=Files.createDirectory(temporary.resolve("clusters"));
        try(var pg=new IsolatedPitrPostgres(root)){
            pg.startSource(Map.of());pg.createDatabase();String[] sourceArgs=arguments(pg,false,root);var catalog=catalog(sourceArgs,Clock.systemUTC());catalog.initialize();start(sourceArgs);
            var boarded=PaidReservationFixture.create(sql,0,"Etc/UTC");var cancelled=PaidReservationFixture.create(sql,1,"Etc/UTC");UUID erased=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,'synthetic-no-login','Synthetic erased after target','2000-01-01',now()) RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");
            UUID dataset=sql.queryForObject("SELECT dataset_id FROM privacy_restore_state",UUID.class);String system=sql.queryForObject("SELECT system_identifier::text FROM pg_control_system()",String.class);
            byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Map<String,byte[]> keys=Map.of("pitr1",key);Path operations=root.resolve("ops");var bundle=PitrRecoveryBundle.reserve(catalog,operations,dataset,system,keys,"pitr1",Clock.systemUTC());Path resource=bundle.resource(operations);
            String encoded=new ObjectMapper().writeValueAsString(Map.of("pitr1",Base64.getEncoder().encodeToString(key)));var sourceEnvironment=Map.of("UNICARONA_PITR_ROOT",resource.toString(),"UNICARONA_PITR_DATA_ROOT",pg.source().toString(),"UNICARONA_PITR_KEYS",encoded);var restoreEnvironment=Map.of("UNICARONA_PITR_ROOT",resource.toString(),"UNICARONA_PITR_DATA_ROOT",pg.restored().toString(),"UNICARONA_PITR_KEYS",encoded);
            stop();pg.stopSource();pg.configureSourceArchive();pg.startSource(sourceEnvironment);start(sourceArgs);
            Instant baseStarted=Instant.now();Path base=resource.resolve("capture-base");pg.baseBackup(base);bundle.archive().sealBase(base);pg.removeOwnedBase(base);
            // Ensure target transactions are in WAL that is absent from the streamed base.
            sql.queryForObject("SELECT pg_switch_wal()::text",String.class);
            var lifecycle=app.getBean(LifecycleTransactions.class);lifecycle.board(actor(boarded.driver()),boarded.id().toString(),boarded.pin(),"pitr-board-"+UUID.randomUUID());lifecycle.cancelRide(actor(cancelled.driver()),cancelled.ride().toString(),"Synthetic PITR cancellation","pitr-cancel-"+UUID.randomUUID());
            assertConsumed(boarded);assertConsumed(cancelled);Timestamp boardedAt=sql.queryForObject("SELECT boarded_at FROM ride_reservations WHERE id=?",Timestamp.class,boarded.id());var expectedMoney=money();var expectedReservations=sql.queryForList("SELECT to_jsonb(r)::text FROM ride_reservations r ORDER BY id",String.class);
            String target="unicarona-"+UUID.randomUUID();String lsn=sql.queryForObject("SELECT pg_create_restore_point(?)::text",String.class,target);String wal=sql.queryForObject("SELECT pg_walfile_name(?::pg_lsn)",String.class,lsn);sql.queryForObject("SELECT pg_switch_wal()::text",String.class);
            long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();while(!Files.isRegularFile(resource.resolve(wal+".gcm"))&&System.nanoTime()<deadline)Thread.sleep(100);assertTrue(Files.isRegularFile(resource.resolve(wal+".gcm")),"Native archive_command must publish the target WAL.");
            bundle.archive().finish(target,lsn,wal);var copy=bundle.finalizeCapture(baseStarted);assertEquals(baseStarted.toEpochMilli(),copy.restorePointEpochMs());assertTrue(copy.restorePointEpochMs()<Instant.now().toEpochMilli());
            sql.execute("ALTER SYSTEM SET archive_command=''");sql.queryForObject("SELECT pg_reload_conf()",Boolean.class);
            // Independent controls deliberately newer than the native replay target.
            UUID hold=UUID.randomUUID();String reason=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.HOLD_REASON,cancelled.passenger(),hold,"Synthetic post-target financial case");
            app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.update("INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,'financial','user','regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",hold,cancelled.passenger(),reason,cancelled.driver());app.getBean(PrivacyHoldRecovery.class).stage();});
            var journal=app.getBean(PrivacyErasureJournal.class);Instant requested=Instant.now();journal.ensure(erased,UUID.randomUUID(),requested,requested.plus(Duration.ofDays(37)));app.getBean(PrivacyErasureMaintenance.class).replay(journal.entries().stream().filter(e->e.owner().equals(erased)).findFirst().orElseThrow());
            assertEquals("deleted",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,erased));assertEquals(expectedMoney,money());stop();pg.stopSource();
            bundle.archive().restoreBase(pg.restored());try{pg.startRestore(target,wal,restoreEnvironment);}catch(Exception failure){throw new AssertionError(pg.restoreDiagnostics(),failure);}sql=pg.sql(true);
            assertEquals(expectedMoney,money());assertEquals(expectedReservations,sql.queryForList("SELECT to_jsonb(r)::text FROM ride_reservations r ORDER BY id",String.class));assertConsumed(boarded);assertConsumed(cancelled);assertEquals(boardedAt,sql.queryForObject("SELECT boarded_at FROM ride_reservations WHERE id=?",Timestamp.class,boarded.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE id=?",Integer.class,hold));assertEquals("active",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,erased));
            start(arguments(pg,true,root));assertTrue(app.getBean(PrivacyReadiness.class).ready());assertEquals(expectedMoney,money());assertEquals(500,app.getBean(LedgerService.class).balance(cancelled.passenger(),AccountKind.PASSENGER_CREDIT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE id=?",Integer.class,hold));assertEquals("deleted",sql.queryForObject("SELECT account_status FROM users WHERE id=?",String.class,erased));assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,erased));stop();pg.stopRestore();pg.removeStoppedRestore();
            // Missing or corrupted final WAL must not start a database short of the named target.
            Path encryptedWal=resource.resolve(wal+".gcm"),quarantine=root.resolve("target-wal-test.private");Files.move(encryptedWal,quarantine);
            bundle.archive().restoreBase(pg.restored());assertThrows(Exception.class,()->pg.startRestore(target,wal,restoreEnvironment));assertTrue(pg.restoreDiagnostics().contains("recovery ended before configured recovery target was reached"),pg.restoreDiagnostics());pg.stopRestore();pg.removeStoppedRestore();Files.move(quarantine,encryptedWal);
            byte[] original=Files.readAllBytes(encryptedWal);byte[] corrupt=original.clone();corrupt[corrupt.length-1]^=1;Files.write(encryptedWal,corrupt);bundle.archive().restoreBase(pg.restored());assertThrows(Exception.class,()->pg.startRestore(target,wal,restoreEnvironment));assertTrue(pg.restoreDiagnostics().contains("recovery ended before configured recovery target was reached"),pg.restoreDiagnostics());pg.stopRestore();pg.removeStoppedRestore();Files.write(encryptedWal,original);
            // The whole finite base+WAL resource expires under the original catalog deadline.
            var futureCatalog=catalog(sourceArgs,Clock.fixed(Instant.ofEpochMilli(copy.expiresAtEpochMs()+1),ZoneOffset.UTC));futureCatalog.locked(()->futureCatalog.prune(10000));assertFalse(Files.exists(resource));assertTrue(Files.isDirectory(root.resolve("journal")));assertTrue(Files.isDirectory(operations.resolve("hold-control")));
        }
    }
}
