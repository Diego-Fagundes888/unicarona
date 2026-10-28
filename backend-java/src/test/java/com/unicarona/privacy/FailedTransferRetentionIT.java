package com.unicarona.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.service.LedgerService;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, complete historical journals, synthetic people and money. */
class FailedTransferRetentionIT {
    @TempDir Path temporary;private static RealPostgres database;private ServletWebServerApplicationContext app;private JdbcTemplate sql;private String[] args;
    @BeforeAll static void database(){database=new RealPostgres();}
    @AfterAll static void databaseClose(){if(database!=null)database.close();}
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    @BeforeEach void setup()throws Exception {
        database.resetSchema();var values=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));values.addAll(List.of("--STORAGE_PATH="+temporary.resolve("files"),"--FRONTEND_PATH="+temporary.resolve("public"),"--PRIVACY_OPERATIONS_ROOT="+temporary.resolve("ops"),"--PRIVACY_ERASURE_JOURNAL_PATH="+temporary.resolve("journal"),"--DEV_MAIL_PATH="+temporary.resolve("mail")));args=values.toArray(String[]::new);
        var env=new MockEnvironment();for(String arg:args){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,Clock.systemUTC()).initialize();start();
    }
    private void start(){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class).run(args);sql=app.getBean(JdbcTemplate.class);}
    private UUID owner(){return sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,'synthetic-not-a-login','Synthetic failed transfer','2000-01-01',now()) RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");}
    private record Group(UUID id,UUID owner,UUID requested,UUID completed,UUID source,UUID pending){}
    private Group group(String type,String result,int age){return group(type,result,age,age);}
    private Group group(String type,String result,int age,int completionAge){
        UUID owner=owner(),id=UUID.randomUUID(),requested=UUID.randomUUID(),completed=UUID.randomUUID();
        return app.getBean(TransactionTemplate.class).execute(ignored->{
            String sourceKind=type.equals("withdraw")?"driver_available":"passenger_credit",pendingKind=type.equals("withdraw")?"payout_pending":"refund_pending";
            UUID source=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,?) RETURNING id",UUID.class,owner,sourceKind),pending=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,?) RETURNING id",UUID.class,owner,pendingKind);
            UUID cash=sql.queryForObject("INSERT INTO ledger_accounts(kind) VALUES('provider_clearing') ON CONFLICT(owner_id,kind,currency) DO UPDATE SET kind=excluded.kind RETURNING id",UUID.class);
            UUID funding=journal("synthetic-funding:"+id,"synthetic_credit",age+2);entries(funding,cash,source,700,age+2);
            sql.update("INSERT INTO transfers(id,user_id,type,cents,cadence,created_at) VALUES(?,?,?,500,CASE WHEN ?='withdraw' THEN 'daily' END,now()-(?+1)*interval '24 hours')",id,owner,type,type,age);
            journal(requested,"transfer:"+id+":requested","transfer_requested",age+1);entries(requested,source,pending,500,age+1);
            if(!result.equals("pending")){
                UUID target=source;if(result.equals("approved"))target=sql.queryForObject("INSERT INTO ledger_accounts(owner_id,kind) VALUES(?,?) RETURNING id",UUID.class,owner,type.equals("withdraw")?"paid_out":"refunded");
                journal(completed,"transfer:"+id+":completed",result.equals("failed")?"transfer_failed":type,completionAge);entries(completed,pending,target,500,completionAge);
                sql.update("UPDATE transfers SET status=?,provider_reference=? WHERE id=?",result,"synthetic-reference:"+id,id);
                sql.update("INSERT INTO webhook_events(provider,event_id,payload_hash,payload,status,received_at,processed_at) VALUES('development-transfer',?,?,jsonb_build_object('eventId',?::text,'transferId',?::text,'reference',?::text,'amountCents',500,'status',?::text),'processed',now()-?*interval '24 hours',now()-?*interval '24 hours')","synthetic-event:"+id,"a".repeat(64),"synthetic-event:"+id,id,"synthetic-reference:"+id,result,completionAge,completionAge);
            }
            return new Group(id,owner,requested,completed,source,pending);
        });
    }
    private UUID journal(String event,String kind,int age){UUID id=UUID.randomUUID();journal(id,event,kind,age);return id;}
    private void journal(UUID id,String event,String kind,int age){sql.update("INSERT INTO ledger_transactions(id,event_key,kind,reason,body_hash,created_at) VALUES(?,?,?,'Synthetic historical event',?,now()-?*interval '24 hours')",id,event,kind,"a".repeat(64),age);}
    private void entries(UUID tx,UUID from,UUID to,int cents,int age){sql.update("INSERT INTO ledger_entries(transaction_id,account_id,cents,created_at) VALUES(?,?,?,now()-?*interval '24 hours'),(?,?,?,now()-?*interval '24 hours')",tx,from,-cents,age,tx,to,cents,age);}
    private int prune()throws Exception{return app.getBean(PrivacyFailedTransferMaintenance.class).runOnce(100,()->false);}
    private boolean exists(Group g){return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM transfers WHERE id=?)",Boolean.class,g.id()));}
    private void restore(Path dump)throws Exception{app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(dump);start();}
    private UUID hold(UUID owner){UUID id=UUID.randomUUID();String reason=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.HOLD_REASON,owner,id,"Synthetic financial case preservation");app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.update("INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,'financial','user','regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",id,owner,reason,owner);app.getBean(PrivacyHoldRecovery.class).stage();});return id;}
    @Test void entireExpiredRefundFailureRetiresWithoutChangingAnyAccount()throws Exception {
        Group g=group("refund","failed",2200);var ledger=app.getBean(LedgerService.class);var before=sql.queryForList("SELECT a.id,coalesce(sum(e.cents),0) AS cents FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id GROUP BY a.id ORDER BY a.id");
        sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,metadata,created_at) VALUES(?,'transfer_requested','transfer',?,jsonb_build_object('type','refund','cents',500),now()-interval '2201 days'),(NULL,'transfer_provider_result','transfer',?,'{\"status\":\"failed\"}',now()-interval '2200 days')",g.owner(),g.id(),g.id());
        sql.update("INSERT INTO notifications(user_id,title,body,route,ref_id) VALUES(?,'Synthetic failed transfer','Synthetic notice','wallet',?)",g.owner(),g.id());sql.update("INSERT INTO outbox_jobs(type,event_key,payload,status) VALUES('transfer',?,jsonb_build_object('transferId',?::text),'done')","transfer:"+g.id(),g.id());
        sql.update("INSERT INTO idempotency_keys(actor_id,key,scope,body_hash,response,expires_at) VALUES(?,'synthetic-cache','transfer:create',?,jsonb_build_object('id',?::text),now()-interval '1 day')",g.owner(),"a".repeat(64),g.id());
        assertEquals(1,prune());assertFalse(exists(g));assertEquals(700,ledger.balance(g.owner(),AccountKind.PASSENGER_CREDIT));assertEquals(0,ledger.reconciliation().difference());assertEquals(before,sql.queryForList("SELECT a.id,coalesce(sum(e.cents),0) AS cents FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id GROUP BY a.id ORDER BY a.id"));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?,?)",Integer.class,g.requested(),g.completed()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM ledger_transactions",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM webhook_events",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM outbox_jobs",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_type='transfer'",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM notifications",Integer.class));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys",Integer.class));assertEquals(0,prune());
    }
    @Test void failedWithdrawalHasTheSameNeutralBoundary()throws Exception{Group g=group("withdraw","failed",2200);assertEquals(1,prune());assertFalse(exists(g));assertEquals(700,app.getBean(LedgerService.class).balance(g.owner(),AccountKind.DRIVER_AVAILABLE));}
    @Test void recentFailurePendingAndSuccessfulMoneyRemainImmutable()throws Exception{var recent=group("refund","failed",1800);var pending=group("withdraw","pending",2200);var approved=group("refund","approved",2200);assertEquals(0,prune());for(var g:List.of(recent,pending,approved)){assertTrue(exists(g));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM transfers WHERE id=?",g.id()));}assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM ledger_entries WHERE transaction_id=?",approved.completed()));}
    @Test void rawPartialJournalOrTransferDeletionRollsBack()throws Exception{var g=group("refund","failed",2200);for(String statement:List.of("DELETE FROM ledger_entries WHERE transaction_id=?","DELETE FROM transfers WHERE id=?")){assertThrows(RuntimeException.class,()->app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->sql.update(statement,statement.contains("ledger_entries")?g.completed():g.id())));}assertTrue(exists(g));assertEquals(4,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?,?)",Integer.class,g.requested(),g.completed()));assertEquals(1,prune());}
    @Test void expiredHistoryStillCannotBeUpdated()throws Exception{var g=group("refund","failed",2200);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE ledger_entries SET cents=cents+1 WHERE transaction_id=?",g.completed()));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE ledger_transactions SET created_at=now() WHERE id=?",g.completed()));assertEquals(1,prune());}
    @Test void unknownOrContradictoryReceiptsPreventDeletion()throws Exception{var g=group("refund","failed",2200);sql.update("UPDATE webhook_events SET provider='future-provider' WHERE payload->>'transferId'=?",g.id().toString());assertEquals(0,prune());assertTrue(exists(g));sql.update("UPDATE webhook_events SET provider='development-transfer',payload=jsonb_set(payload,'{status}','\"approved\"')");assertEquals(0,prune());assertTrue(exists(g));}
    @Test void unprocessedReceiptAndActiveDeliveryPreventDeletion()throws Exception{var g=group("refund","failed",2200);sql.update("UPDATE webhook_events SET status='pending'");assertEquals(0,prune());sql.update("UPDATE webhook_events SET status='processed'");sql.update("INSERT INTO outbox_jobs(type,event_key,payload,status,leased_until) VALUES('transfer',?,jsonb_build_object('transferId',?::text),'processing',now()+interval '1 day')","transfer:"+g.id(),g.id());assertEquals(0,prune());assertTrue(exists(g));sql.update("UPDATE outbox_jobs SET status='done',leased_until=NULL");assertEquals(1,prune());}
    @Test void unknownAuditCannotBeSilentlyRemoved()throws Exception{var g=group("refund","failed",2200);sql.update("INSERT INTO audit_logs(action,resource_type,resource_id,created_at) VALUES('future-financial-review','transfer',?,now()-interval '2200 days')",g.id());assertEquals(0,prune());assertTrue(exists(g));}
    @Test void validCacheDefersTheGraphAndExpiredCacheDoesNot()throws Exception{var g=group("refund","failed",2200);sql.update("INSERT INTO idempotency_keys(actor_id,key,scope,body_hash,response,expires_at) VALUES(?,'live','transfer:create',?,jsonb_build_object('id',?::text),now()+interval '1 day')",g.owner(),"b".repeat(64),g.id());assertEquals(0,prune());sql.update("UPDATE idempotency_keys SET expires_at=now()-interval '1 day'");assertEquals(1,prune());}
    @Test void ownerHoldProtectsOnlyItsOwnGraph()throws Exception{var held=group("refund","failed",2200);var other=group("withdraw","failed",2200);hold(held.owner());assertEquals(1,prune());assertTrue(exists(held));assertFalse(exists(other));}
    @Test void newerIndependentHoldReplaysBeforeOldGraphCanExpire()throws Exception{var g=group("refund","failed",2200);Path dump=temporary.resolve("before-failed-transfer-hold.dump");database.dumpDatabaseArchive(dump);UUID h=hold(g.owner());restore(dump);assertTrue(exists(g));assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE id=?",Integer.class,h));assertTrue(app.getBean(PrivacyReadiness.class).ready());app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE id=?",h);app.getBean(PrivacyHoldRecovery.class).stage();});assertEquals(1,prune());assertFalse(exists(g));}
    @Test void restoredExpiredNeutralGraphIsRemovedBeforeReadiness()throws Exception{var g=group("refund","failed",2200);Path dump=temporary.resolve("expired-failed-transfer.dump");database.dumpDatabaseArchive(dump);assertEquals(1,prune());restore(dump);assertFalse(exists(g));assertEquals(700,app.getBean(LedgerService.class).balance(g.owner(),AccountKind.PASSENGER_CREDIT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());assertTrue(app.getBean(PrivacyReadiness.class).ready());}
    @Test void futureForeignKeyFailureRollsBackTheWholeGraph()throws Exception{var g=group("refund","failed",2200);sql.execute("CREATE TABLE synthetic_transfer_dependency(id uuid PRIMARY KEY REFERENCES transfers)");sql.update("INSERT INTO synthetic_transfer_dependency VALUES(?)",g.id());assertThrows(org.springframework.dao.DataIntegrityViolationException.class,this::prune);assertTrue(exists(g));assertEquals(4,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?,?)",Integer.class,g.requested(),g.completed()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM webhook_events",Integer.class));assertEquals(700,app.getBean(LedgerService.class).balance(g.owner(),AccountKind.PASSENGER_CREDIT));}
    @Test void retainedIdentityCannotLoseItsKnownFinancialSourcesEvenAfterPurposeTampering()throws Exception {
        var g=group("refund","failed",2200);UUID copy=UUID.randomUUID();String cipher=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,g.owner(),copy,"{\"name\":\"Synthetic financial claimant\",\"email\":\"claimant@example.test\",\"phone\":null}");sql.update("INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,delete_after) VALUES(?,?,'contract_identity','user',?,?,'Synthetic retained claim','regular_exercise_of_rights',now()+interval '5 years')",copy,g.owner(),g.owner(),cipher);
        app.getBean(PrivacyRetentionTransactions.class).prune(100);assertEquals(0,prune());long sequence=app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().head().sequence();
        sql.update("UPDATE privacy_retained_records SET financial_purpose=jsonb_set(financial_purpose,'{facts}','[]') WHERE id=?",copy);assertThrows(Exception.class,()->app.getBean(PrivacyFailedTransferRetention.class).retire(new PrivacyFailedTransferRetention.Candidate(g.id(),g.owner()),false));assertTrue(exists(g));assertEquals(4,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?,?)",Integer.class,g.requested(),g.completed()));assertEquals(sequence,app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().head().sequence());assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void busyOwnerDoesNotStarveOtherExpiredGraphs()throws Exception {
        var busy=group("refund","failed",2200);var free=group("withdraw","failed",2200);
        try(var connection=database.dataSource().getConnection()){connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){statement.setObject(1,busy.owner());statement.executeQuery().close();}assertEquals(1,prune());assertTrue(exists(busy));assertFalse(exists(free));connection.rollback();}assertEquals(1,prune());
    }
    @Test void accessRequestDefersExpiryButCompletedAccessDoesNotCreateANewClock()throws Exception {
        var g=group("refund","failed",2200);UUID request=UUID.randomUUID();String detail=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,g.owner(),request,"Synthetic access request");sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,due_at) VALUES(?,?,'access',?,now()+interval '15 days')",request,g.owner(),detail);assertEquals(0,prune());sql.update("UPDATE privacy_requests SET status='completed',resolved_at=now() WHERE id=?",request);assertEquals(1,prune());
    }
    @Test void technicalPollingAndDuplicateReceiptDoNotRestartFiveYears()throws Exception {
        var g=group("refund","failed",2200);sql.update("UPDATE transfers SET updated_at=now() WHERE id=?",g.id());sql.update("INSERT INTO webhook_events(provider,event_id,payload_hash,payload,status,received_at,processed_at) SELECT provider,'synthetic-repeat',payload_hash,jsonb_set(payload,'{eventId}','\"synthetic-repeat\"'),'processed',now(),now() FROM webhook_events WHERE payload->>'transferId'=?",g.id().toString());assertEquals(1,prune());assertFalse(exists(g));
    }
    @Test void bulkTruncateCannotBypassJournalProtection()throws Exception{var g=group("refund","failed",2200);assertThrows(RuntimeException.class,()->sql.execute("TRUNCATE ledger_entries"));assertTrue(exists(g));assertEquals(4,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?,?)",Integer.class,g.requested(),g.completed()));}
    @Test void retiringOnlyMoneyWithoutItsKnownReceiptCannotCommit()throws Exception {
        var g=group("refund","failed",2200);assertThrows(RuntimeException.class,()->app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->sql.queryForObject("""
            WITH entries AS(DELETE FROM ledger_entries WHERE transaction_id IN (?,?) RETURNING id),
            journals AS(DELETE FROM ledger_transactions WHERE id IN (?,?) AND (SELECT count(*) FROM entries)=4 RETURNING id),
            transfer AS(DELETE FROM transfers WHERE id=? AND (SELECT count(*) FROM journals)=2 RETURNING id)
            SELECT count(*) FROM transfer
            """,Integer.class,g.requested(),g.completed(),g.requested(),g.completed(),g.id())));assertTrue(exists(g));assertEquals(4,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id IN (?,?)",Integer.class,g.requested(),g.completed()));assertEquals(1,prune());
    }
    @Test void originalRequestAgeCannotExpireANewlyCompletedFailure()throws Exception{var g=group("refund","failed",2200,1);assertEquals(0,prune());assertTrue(exists(g));}
    @Test void resolutionOfAFinancialCaseCreatesItsOwnDefenceWindow()throws Exception {
        var g=group("refund","failed",2200);UUID incident=sql.queryForObject("INSERT INTO occurrences(reporter_id,kind,reason,status,created_at) VALUES(?,'dispute','Synthetic pending financial case','pending',now()-interval '1 day') RETURNING id",UUID.class,g.owner());assertEquals(0,prune());sql.update("UPDATE occurrences SET status='resolved',outcome='no_financial_change',reviewed_by=?,reviewed_at=now(),decision='Synthetic financial review' WHERE id=?",g.owner(),incident);assertEquals(0,prune());assertTrue(exists(g));
    }
}
