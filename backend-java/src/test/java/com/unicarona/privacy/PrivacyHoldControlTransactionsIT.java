package com.unicarona.privacy;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.config.*;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.support.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL COMMIT/ROLLBACK and durable files; this does not certify typed retention replay. */
class PrivacyHoldControlTransactionsIT {
    @TempDir Path temporary;private static RealPostgres database;
    private JdbcTemplate sql;private TransactionTemplate transaction;private DataSourceTransactionManager manager;private PrivacyHoldControlTransactions controls;private PrivacyHoldControlJournal journal;private PrivacyReadiness readiness;private Path operations;private UUID owner;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    @BeforeAll static void start(){database=new RealPostgres();}
    @AfterAll static void close(){if(database!=null)database.close();}
    @BeforeEach void setup()throws Exception {
        database.resetSchema();var source=database.dataSource();new LegacyFlywayMigrationStrategy(source,new LegacyMigrationManifest()).migrate(Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load());sql=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);transaction=new TransactionTemplate(manager);
        var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET).withProperty("STORAGE_PATH",temporary.resolve("active").toString()).withProperty("FRONTEND_PATH",temporary.resolve("public").toString()).withProperty("PRIVACY_OPERATIONS_ROOT",temporary.resolve("ops").toString());
        var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);var keys=new PrivacySigningKeys(env,auth,crypto,json);var catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),keys,json,Clock.systemUTC());catalog.initialize();UUID dataset=sql.queryForObject("SELECT dataset_id FROM privacy_restore_state WHERE singleton",UUID.class);catalog.locked(()->{catalog.bindDataset(dataset);return null;});
        journal=new PrivacyHoldControlJournal(catalog,new PrivacyEvidenceCrypto(env,auth,crypto,json),keys,json,Clock.systemUTC());operations=temporary.resolve("ops");readiness=new PrivacyReadiness();readiness.complete();controls=new PrivacyHoldControlTransactions(sql,journal,readiness,manager);
        owner=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date) VALUES(?,'synthetic non-authentication fixture','Synthetic control owner','2000-01-01') RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");
    }
    private UUID hold(){return sql.queryForObject("INSERT INTO privacy_legal_holds(user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,'profile','user',?,'regular_exercise_of_rights','Synthetic encrypted-content placeholder',?,now()+interval '1 hour',now()+interval '1 day') RETURNING id",UUID.class,owner,owner,owner);}
    private JsonNode state(){return json.createObjectNode().put("syntheticActiveHolds",sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE released_at IS NULL",Integer.class));}
    @Test void commitPublishesItsMatchingControlWhileARolledBackReleaseNeverReplacesIt()throws Exception {
        UUID id=transaction.execute(status->{UUID hold=hold();controls.stage(this::state);return hold;});assertFalse(controls.marker().pending());assertEquals(1,controls.marker().sequence());assertEquals(1,journal.current().orElseThrow().state().path("syntheticActiveHolds").asInt());
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->{sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE id=?",id);controls.stage(this::state);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("Synthetic failure after preparing a release");}});}));
        assertNull(sql.queryForObject("SELECT released_at FROM privacy_legal_holds WHERE id=?",java.sql.Timestamp.class,id));assertEquals(1,controls.marker().sequence());assertFalse(controls.marker().pending());assertTrue(journal.pending().isEmpty());assertEquals(1,journal.current().orElseThrow().state().path("syntheticActiveHolds").asInt());assertTrue(readiness.ready());
    }
    @Test void crashAfterDatabaseCommitBlocksRetentionUntilThatExactCommittedMarkerIsConfirmed()throws Exception {
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status->{hold();controls.stage(this::state);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MIN_VALUE;}@Override public void afterCommit(){throw new IllegalStateException("Synthetic crash before external confirmation");}});}));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds",Integer.class));assertTrue(controls.marker().pending());assertFalse(readiness.ready());assertTrue(journal.pending().isPresent());
        var blocked=assertThrows(ApiException.class,()->transaction.executeWithoutResult(status->PrivacyRetentionLock.acquire(sql)));assertEquals(503,blocked.status());assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_legal_holds SET released_at=now()"));
        controls.confirmKnownCommittedOutcome();assertFalse(controls.marker().pending());assertTrue(journal.pending().isEmpty());assertEquals(1,journal.current().orElseThrow().state().path("syntheticActiveHolds").asInt());assertFalse(readiness.ready(),"Only the full recovery guard may reopen the service");
    }
    @Test void aPreparedRecordWithoutItsCommittedMarkerCannotBeGuessedToBeRolledBack()throws Exception {
        var prepared=journal.prepare(json.createObjectNode().put("syntheticUncertainProposal",true));var unavailable=assertThrows(ApiException.class,controls::confirmKnownCommittedOutcome);assertEquals(503,unavailable.status());assertFalse(readiness.ready());assertTrue(journal.pending().isPresent());assertTrue(Files.exists(operations.resolve("hold-control").resolve(prepared.target().file())));assertEquals(0,controls.marker().sequence());
    }
    @Test void externalCommitBeforeMarkerConfirmationCanRecoverWithoutApprovingAnotherOperation()throws Exception {
        sql.execute("CREATE FUNCTION synthetic_confirmation_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NOT NEW.needs_confirmation THEN RAISE EXCEPTION 'Synthetic confirmation interruption'; END IF; RETURN NEW; END $$");sql.execute("CREATE TRIGGER synthetic_confirmation_failure BEFORE UPDATE ON privacy_hold_control_state FOR EACH ROW EXECUTE FUNCTION synthetic_confirmation_failure()");
        assertThrows(ApiException.class,()->transaction.executeWithoutResult(status->{hold();controls.stage(this::state);}));assertTrue(controls.marker().pending());assertTrue(journal.pending().isEmpty());assertTrue(journal.current().isPresent());assertFalse(readiness.ready());
        sql.execute("DROP TRIGGER synthetic_confirmation_failure ON privacy_hold_control_state");sql.execute("DROP FUNCTION synthetic_confirmation_failure()");controls.confirmKnownCommittedOutcome();assertFalse(controls.marker().pending());assertEquals(journal.current().orElseThrow().head().operation(),controls.marker().operation());
    }
    @Test void checkpointCannotRegressLoseIdentityOrBeConfirmedByAnOrdinaryUpdate()throws Exception {
        transaction.executeWithoutResult(status->{hold();controls.stage(this::state);});assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_hold_control_state SET sequence=0,operation_id=NULL"));assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_hold_control_state SET operation_id=?",UUID.randomUUID()));assertThrows(RuntimeException.class,()->sql.update("DELETE FROM privacy_hold_control_state"));assertThrows(RuntimeException.class,()->sql.execute("TRUNCATE privacy_hold_control_state"));
        sql.update("UPDATE privacy_hold_control_state SET needs_confirmation=true");assertThrows(RuntimeException.class,()->sql.update("UPDATE privacy_hold_control_state SET needs_confirmation=false"));controls.confirmKnownCommittedOutcome();assertFalse(controls.marker().pending());
    }
    @Test void severalStageCallsInOneTransactionHaveOnePreparationAndCaptureTheFinalState()throws Exception {
        transaction.executeWithoutResult(status->{UUID hold=hold();controls.stage(this::state);sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE id=?",hold);controls.stage(this::state);});assertEquals(1,controls.marker().sequence());assertEquals(0,journal.current().orElseThrow().state().path("syntheticActiveHolds").asInt());assertThrows(IllegalStateException.class,()->controls.stage(this::state));
        transaction.executeWithoutResult(status->controls.stage(this::state));assertEquals(1,controls.marker().sequence(),"A maintenance no-op must not create another durable control revision");
    }
    @Test void anEstablishedCheckpointCannotBeDisabledByRemovingTheOperationsConfiguration()throws Exception {
        transaction.executeWithoutResult(status->{hold();controls.stage(this::state);});var env=new MockEnvironment().withProperty("NODE_ENV","test").withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY).withProperty("JWT_SECRET",AuthTestSettings.SECRET).withProperty("STORAGE_PATH",temporary.resolve("active").toString()).withProperty("FRONTEND_PATH",temporary.resolve("public").toString()).withProperty("PRIVACY_OPERATIONS_ROOT","");
        var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);var keys=new PrivacySigningKeys(env,auth,crypto,json);var catalog=new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),keys,json,Clock.systemUTC());var disabled=new PrivacyHoldControlJournal(catalog,new PrivacyEvidenceCrypto(env,auth,crypto,json),keys,json,Clock.systemUTC());var withoutControl=new PrivacyHoldControlTransactions(sql,disabled,readiness,manager);
        assertThrows(ApiException.class,withoutControl::confirmKnownCommittedOutcome);assertFalse(readiness.ready());assertThrows(ApiException.class,()->transaction.executeWithoutResult(status->withoutControl.stage(this::state)));assertEquals(1,controls.marker().sequence());assertTrue(journal.current().isPresent());
    }
}
