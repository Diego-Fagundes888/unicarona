package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Financial identity evidence and real PostgreSQL; synthetic money and persons only. */
class FinancialIdentityRetentionIT {
    @TempDir Path temporary;private static RealPostgres database;private ServletWebServerApplicationContext app;private JdbcTemplate sql;private String[] args;
    @BeforeAll static void database(){database=new RealPostgres();}
    @AfterAll static void databaseClose(){if(database!=null)database.close();}
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    @BeforeEach void setup()throws Exception {
        database.resetSchema();var values=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));values.addAll(List.of("--STORAGE_PATH="+temporary.resolve("files"),"--FRONTEND_PATH="+temporary.resolve("public"),"--PRIVACY_OPERATIONS_ROOT="+temporary.resolve("ops"),"--PRIVACY_ERASURE_JOURNAL_PATH="+temporary.resolve("journal"),"--DEV_MAIL_PATH="+temporary.resolve("mail")));args=values.toArray(String[]::new);
        var env=new MockEnvironment();for(String arg:args){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,Clock.systemUTC()).initialize();
        start();
    }
    private void start(){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args);sql=app.getBean(JdbcTemplate.class);}
    private UUID closedOwner(){UUID id=UUID.randomUUID();sql.update("INSERT INTO users(id,email,password_hash,name,birth_date,email_verified_at,account_status,deleted_at,created_at) VALUES(?,?,'synthetic non-authentication fixture','Conta excluída',NULL,NULL,'deleted',now()-interval '6 years',now()-interval '7 years')",id,"deleted-"+id+"@deleted.invalid");return id;}
    private UUID identity(UUID owner)throws Exception {
        UUID id=UUID.randomUUID();String cipher=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,owner,id,"{\"name\":\"Synthetic financial claimant\",\"email\":\"claimant@example.test\",\"phone\":null}");
        sql.update("INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,created_at,delete_after,hold_only) VALUES(?,?,'contract_identity','user',?,?,'Synthetic minimum identity for financial obligations','regular_exercise_of_rights',now()-interval '6 years',now()-interval '1 year',false)",id,owner,owner,cipher);return id;
    }
    private void credit(UUID owner){historicalBalance(owner,"passenger_credit",500);}
    private void historicalBalance(UUID owner,String kind,int cents){historicalBalance(owner,kind,cents,Instant.now().atOffset(java.time.ZoneOffset.UTC).minusYears(7).toInstant());}
    private void historicalBalance(UUID owner,String kind,int cents,Instant happened){
        // Historical money is inserted coherently, never backdated by bypassing
        // immutable history. This balance was already outstanding at closure.
        app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{
            UUID cash=sql.queryForObject("INSERT INTO ledger_accounts(kind) VALUES('provider_clearing') ON CONFLICT(owner_id,kind,currency) DO UPDATE SET kind=excluded.kind RETURNING id",UUID.class);
            UUID credit=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,?) RETURNING id",UUID.class,owner,kind);
            UUID transaction=sql.queryForObject("INSERT INTO ledger_transactions(event_key,kind,reason,body_hash,created_at) VALUES(?,'synthetic_credit','Synthetic historical refundable value',?,?) RETURNING id",UUID.class,"synthetic-financial-identity:"+UUID.randomUUID(),"a".repeat(64),Timestamp.from(happened));
            sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents,created_at) VALUES(?,?,?,?),(?,?,?,?)",transaction,cash,-cents,Timestamp.from(happened),transaction,credit,cents,Timestamp.from(happened));
        });
    }
    private void prune(){app.getBean(PrivacyRetentionTransactions.class).prune(100);}
    private boolean exists(UUID record){return sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_retained_records WHERE id=?)",Boolean.class,record);}
    private com.fasterxml.jackson.databind.JsonNode purpose(UUID record)throws Exception{return app.getBean(ObjectMapper.class).readTree(sql.queryForObject("SELECT financial_purpose::text FROM privacy_retained_records WHERE id=?",String.class,record));}
    private void restore(Path archive)throws Exception{app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(archive);start();}
    private void settle(UUID owner){app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->app.getBean(LedgerService.class).post(LedgerCommand.transfer("synthetic-identity-settlement:"+UUID.randomUUID(),"synthetic_refund","Synthetic final refund result only",List.of(new LedgerCommand.Posting(owner,AccountKind.PASSENGER_CREDIT,-500),new LedgerCommand.Posting(owner,AccountKind.REFUNDED,500)))));}
    @Test void outstandingRefundableCreditMustNotLoseItsOnlyClaimantIdentityAtTheOriginalDeadline()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);credit(owner);assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));
        app.getBean(PrivacyRetentionTransactions.class).prune(100);
        assertTrue(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_retained_records WHERE id=?)",Boolean.class,record),"An unresolved refundable balance still needs the minimum claimant identity; closure date alone cannot end that purpose.");
        assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void terminalPaidOutTotalsDoNotKeepExpiredIdentityForever()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);historicalBalance(owner,"paid_out",500);prune();assertFalse(exists(record));assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.PAID_OUT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void anOutstandingReceivableAlsoKeepsItsMinimumIdentity()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);historicalBalance(owner,"passenger_receivable",-500);prune();assertTrue(exists(record));assertTrue(purpose(record).path("pending").asBoolean());assertEquals(-500,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_RECEIVABLE));
    }
    @Test void finalSettlementUsesTheBusinessEventAndRepeatedMaintenanceDoesNotRenewIt()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);credit(owner);prune();int revision=purpose(record).path("revision").asInt();settle(owner);prune();assertFalse(purpose(record).path("pending").asBoolean());assertTrue(purpose(record).path("revision").asInt()>revision);Timestamp until=sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record);assertTrue(until.toInstant().isAfter(Instant.now().plusSeconds(4L*365*86400)));String frozen=purpose(record).toString();prune();assertEquals(until,sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record));assertEquals(frozen,purpose(record).toString());assertEquals(0,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.REFUNDED));
    }
    @Test void aPostBackupPurposePreservesIdentityWithoutInventingLostMoney()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);Path archive=temporary.resolve("before-financial-fact.dump");database.dumpDatabaseArchive(archive);credit(owner);prune();assertTrue(purpose(record).path("pending").asBoolean());restore(archive);assertTrue(app.getBean(PrivacyReadiness.class).ready());assertTrue(exists(record));prune();assertTrue(exists(record));assertEquals(0,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));
        var value=app.getBean(PrivacyContractIdentityPurpose.class).read(purpose(record).toString());assertFalse(app.getBean(PrivacyContractIdentityPurpose.class).factsPresent(owner,"contract_identity",owner,value));assertTrue(value.pending());
    }
    @Test void genuineFormatTwoRecoveryLearnsPurposeWithoutInventingMoneyOrANewClock()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);credit(owner);Timestamp until=sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record);
        Path archive=temporary.resolve("legacy-two-financial-purpose.dump");database.dumpDatabaseArchive(archive);prune();
        var legacy=app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().state().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy).put("format",2);
        for(var row:legacy.path("retained"))((com.fasterxml.jackson.databind.node.ObjectNode)row).remove("purpose");
        app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->app.getBean(PrivacyHoldControlTransactions.class).stage(()->legacy));
        restore(archive);assertTrue(app.getBean(PrivacyReadiness.class).ready());assertTrue(exists(record));
        assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));
        assertEquals(until,sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record));
        assertTrue(purpose(record).path("pending").asBoolean());assertEquals(3,app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().state().path("format").asInt());
    }
    @Test void missingFactsCannotBeDisguisedAsSettlementByDroppingTheBoundary()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);Path archive=temporary.resolve("before-financial-boundary.dump");database.dumpDatabaseArchive(archive);credit(owner);prune();restore(archive);long sequence=app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().head().sequence();
        sql.update("UPDATE privacy_retained_records SET financial_purpose=jsonb_set(jsonb_set(financial_purpose,'{pending}','false'),'{facts}','[]') WHERE id=?",record);assertThrows(RuntimeException.class,this::prune);assertTrue(exists(record));assertEquals(sequence,app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().head().sequence());assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void anOldVehicleCopyIsNotKeptByAnUnrelatedPassengerCredit()throws Exception {
        UUID owner=closedOwner(),claimant=identity(owner),vehicle=UUID.randomUUID(),record=UUID.randomUUID();String cipher=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,owner,record,"{\"id\":\""+vehicle+"\",\"model\":\"Synthetic old vehicle\",\"color\":\"Black\",\"plate\":\"ABC1D23\"}");sql.update("INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,created_at,delete_after,hold_only) VALUES(?,?,'vehicle_identity','vehicle',?,?,'Synthetic retired contract vehicle','regular_exercise_of_rights',now()-interval '6 years',now()-interval '1 year',false)",record,owner,vehicle,cipher);credit(owner);prune();assertTrue(exists(claimant));assertFalse(exists(record));assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));
    }
    @Test void restoredClosedExpiredEvidenceIsRemovedWithoutARecoveryTimeExtension()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);historicalBalance(owner,"refunded",500);Path archive=temporary.resolve("closed-expired-identity.dump");database.dumpDatabaseArchive(archive);prune();assertFalse(exists(record));restore(archive);assertTrue(app.getBean(PrivacyReadiness.class).ready());assertFalse(exists(record));assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.REFUNDED));
    }
    @Test void closedCopyWithMissingRecordedFactsCannotExpireBasedOnAnOlderEmptyWallet()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);Path archive=temporary.resolve("before-final-business-fact.dump");database.dumpDatabaseArchive(archive);historicalBalance(owner,"refunded",500,Instant.now().atOffset(java.time.ZoneOffset.UTC).minusYears(5).plusSeconds(8).toInstant());prune();assertFalse(purpose(record).path("pending").asBoolean());Timestamp original=sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record);long remaining=java.time.Duration.between(Instant.now(),original.toInstant()).toMillis();assertTrue(remaining<15000);if(remaining>0)Thread.sleep(remaining+100);restore(archive);assertTrue(exists(record));prune();assertTrue(exists(record));assertFalse(purpose(record).path("pending").asBoolean());assertEquals(original,sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record));assertEquals(0,app.getBean(LedgerService.class).balance(owner,AccountKind.REFUNDED));
    }
    private void verifiedObservation(UUID debt,int version,Instant received)throws Exception {
        var payload=Map.of("payment",debt.toString(),"state","APPROVED","version",version,"amountCents",500);String encoded=app.getBean(ObjectMapper.class).writeValueAsString(payload);String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        sql.update("INSERT INTO webhook_events(provider,event_id,payload_hash,payload,status,received_at,processed_at) VALUES('mercadopago',?,?,?::jsonb,'processed',?,?)","synthetic-semantic:"+UUID.randomUUID(),hash,encoded,Timestamp.from(received),Timestamp.from(received));
    }
    @Test void providerVersionOnlyObservationsDoNotRenewTheDefenceWindowOrPurposeRevision()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);UUID debt=sql.queryForObject("INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email,state,order_id,paid_at,created_at) VALUES(?,500,NULL,'APPROVED',?,now()-interval '6 years',now()-interval '7 years') RETURNING id",UUID.class,owner,"ORDTST"+UUID.randomUUID().toString().replace("-",""));verifiedObservation(debt,1,Instant.now().atOffset(java.time.ZoneOffset.UTC).minusYears(1).toInstant());prune();assertTrue(exists(record));Timestamp until=sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record);String before=purpose(record).toString();verifiedObservation(debt,2,Instant.now());sql.update("UPDATE passenger_debt_payments SET provider_version=provider_version+1,updated_at=now(),next_check_at=now() WHERE id=?",debt);prune();assertEquals(until,sql.queryForObject("SELECT delete_after FROM privacy_retained_records WHERE id=?",Timestamp.class,record));assertEquals(before,purpose(record).toString());
    }
    @Test void expiredProtocolDetailsLeaveOnlyAMinimumWitnessUntilTheDependentIdentityExpires()throws Exception {
        UUID owner=closedOwner(),record=identity(owner),protocol=UUID.randomUUID();credit(owner);
        String detail=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,owner,protocol,"Synthetic expired private request details");
        sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,status,created_at,due_at,resolved_at) VALUES(?,?,'other',?,'completed',now()-interval '7 years',now()-interval '7 years'+interval '15 days',now()-interval '6 years')",protocol,owner,detail);
        UUID response=UUID.randomUUID();String body=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.RESPONSE_BODY,owner,response,"Synthetic expired private response");sql.update("INSERT INTO privacy_request_responses(id,request_id,author_id,body_ciphertext) VALUES(?,?,?,?)",response,protocol,owner,body);
        prune();assertTrue(exists(record));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_requests WHERE id=?",Integer.class,protocol),"Keep only the dependency witness, not the private narrative.");
        assertNotEquals(detail,sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,protocol));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_request_responses WHERE request_id=?",Integer.class,protocol));
        app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{
            UUID credit=sql.queryForObject("SELECT id FROM ledger_accounts WHERE owner_id=? AND kind='passenger_credit'",UUID.class,owner),refunded=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,'refunded') RETURNING id",UUID.class,owner);
            UUID tx=sql.queryForObject("INSERT INTO ledger_transactions(event_key,kind,reason,body_hash,created_at) VALUES(?,'synthetic_refund','Synthetic historical final settlement',?,now()-interval '6 years') RETURNING id",UUID.class,"synthetic-old-settlement:"+UUID.randomUUID(),"b".repeat(64));sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents) VALUES(?,?,-500),(?,?,500)",tx,credit,tx,refunded);
        });
        for(int i=0;i<6;i++)prune();assertFalse(exists(record));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_requests WHERE id=?",Integer.class,protocol));assertEquals(0,app.getBean(LedgerService.class).balance(owner,AccountKind.PASSENGER_CREDIT));assertEquals(500,app.getBean(LedgerService.class).balance(owner,AccountKind.REFUNDED));
    }
    private UUID expiredProtocol(UUID owner){
        UUID id=UUID.randomUUID();String detail=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,owner,id,"Synthetic expired protocol narrative");
        sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,status,created_at,due_at,resolved_at) VALUES(?,?,'other',?,'completed',now()-interval '7 years',now()-interval '7 years'+interval '15 days',now()-interval '6 years')",id,owner,detail);return id;
    }
    @Test void restoringAnOldProtocolDoesNotExposeItsExpiredNarrativeBeforeReadiness()throws Exception {
        UUID owner=closedOwner(),record=identity(owner);credit(owner);UUID protocol=expiredProtocol(owner);Timestamp resolved=sql.queryForObject("SELECT resolved_at FROM privacy_requests WHERE id=?",Timestamp.class,protocol);
        String original=sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,protocol);Path archive=temporary.resolve("expired-protocol-narrative.dump");database.dumpDatabaseArchive(archive);prune();restore(archive);
        assertTrue(app.getBean(PrivacyReadiness.class).ready());assertTrue(exists(record));assertNotEquals(original,sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,protocol));assertNotNull(sql.queryForObject("SELECT details_retired_at FROM privacy_requests WHERE id=?",Timestamp.class,protocol));assertEquals(resolved,sql.queryForObject("SELECT resolved_at FROM privacy_requests WHERE id=?",Timestamp.class,protocol));
    }
    @Test void aSpecificProtocolHoldKeepsTheNarrativeUntilItsOwnRelease()throws Exception {
        UUID owner=closedOwner();identity(owner);credit(owner);UUID protocol=expiredProtocol(owner),hold=UUID.randomUUID();String original=sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,protocol);
        String reason=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.HOLD_REASON,owner,hold,"Synthetic justified preservation");sql.update("INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,resource_id,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,'privacy_request','privacy_request',?,'regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",hold,owner,protocol,reason,owner);
        prune();assertEquals(original,sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,protocol));assertNull(sql.queryForObject("SELECT details_retired_at FROM privacy_requests WHERE id=?",Timestamp.class,protocol));
        sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE id=?",hold);prune();assertNotEquals(original,sql.queryForObject("SELECT details_ciphertext FROM privacy_requests WHERE id=?",String.class,protocol));assertNotNull(sql.queryForObject("SELECT details_retired_at FROM privacy_requests WHERE id=?",Timestamp.class,protocol));
    }
    @Test void recoveryWaitsForABusyOwnerRatherThanSkippingAnExpiredNarrative()throws Exception {
        UUID owner=closedOwner(),protocol=expiredProtocol(owner);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();java.util.concurrent.Future<?> recovery=null;
        try(var connection=database.dataSource().getConnection()){
            connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){lock.setObject(1,owner);lock.executeQuery().close();}
            var entered=new java.util.concurrent.CountDownLatch(1);
            recovery=executor.submit(()->{entered.countDown();app.getBean(PrivacyRetentionTransactions.class).beforeReadiness();});
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));Thread.sleep(300);
            assertFalse(recovery.isDone(),"Recovery cannot declare this cleanup complete by skipping the locked owner.");connection.rollback();
            recovery.get(30,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_requests WHERE id=?",Integer.class,protocol));
        }finally{executor.shutdown();if(!executor.awaitTermination(30,java.util.concurrent.TimeUnit.SECONDS))executor.shutdownNow();}
    }
    private PrivacyErasureTransactions.Task preparedScrub(String kind){
        UUID owner=UUID.randomUUID();sql.update("INSERT INTO users(id,email,password_hash,name,birth_date,email_verified_at,account_status) VALUES(?,?,'synthetic non-authentication fixture','Synthetic delayed claimant','2000-01-01',now(),'deleted')",owner,owner+"@example.test");historicalBalance(owner,kind,500);Instant prepared=Instant.now().atOffset(java.time.ZoneOffset.UTC).minusYears(6).toInstant(),until=Instant.now().plusSeconds(40L*86400);
        UUID closure=sql.queryForObject("INSERT INTO privacy_account_closures(user_id,initiated_by,status,previous_account_status,requested_at,reauthenticated_at,erase_after,policy_version) VALUES(?,'user','erasing','active',?,?,?,'synthetic-purpose-fixture') RETURNING id",UUID.class,owner,Timestamp.from(prepared),Timestamp.from(prepared),Timestamp.from(prepared));
        sql.update("INSERT INTO privacy_erasure_tasks(closure_id,user_id,status,prepared_at,suppression_until,journaled_at) VALUES(?,?,'prepared',?,?,now())",closure,owner,Timestamp.from(prepared),Timestamp.from(until));Instant stored=sql.queryForObject("SELECT prepared_at FROM privacy_erasure_tasks WHERE closure_id=?",Timestamp.class,closure).toInstant();return new PrivacyErasureTransactions.Task(closure,owner,stored,until,"prepared");
    }
    @Test void delayedScrubDoesNotCreateANewClosedIdentityAfterItsDefenceWindow()throws Exception {
        // Component fixture at the already-prepared/journaled boundary, with a
        // current physical-backup suppression horizon. Signed replay is tested separately.
        var task=preparedScrub("paid_out");app.getBean(PrivacyErasureTransactions.class).scrub(task);assertEquals("Conta excluída",sql.queryForObject("SELECT name FROM users WHERE id=?",String.class,task.owner()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM privacy_retained_records WHERE user_id=? AND category='contract_identity'",Integer.class,task.owner()));assertEquals(500,app.getBean(LedgerService.class).balance(task.owner(),AccountKind.PAID_OUT));
    }
    @Test void delayedScrubKeepsAnOpenClaimWithoutInventingANewDefenceClock()throws Exception {
        var task=preparedScrub("passenger_credit");app.getBean(PrivacyErasureTransactions.class).scrub(task);var row=sql.queryForMap("SELECT * FROM privacy_retained_records WHERE user_id=? AND category='contract_identity'",task.owner());assertEquals(task.prepared().atOffset(java.time.ZoneOffset.UTC).plusYears(5).toInstant(),((Timestamp)row.get("delete_after")).toInstant());assertTrue(((Timestamp)row.get("created_at")).toInstant().isAfter(Instant.now().minusSeconds(30)));assertTrue(purpose((UUID)row.get("id")).path("pending").asBoolean());assertEquals(500,app.getBean(LedgerService.class).balance(task.owner(),AccountKind.PASSENGER_CREDIT));
    }
}
