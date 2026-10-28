package com.unicarona.payment.provider;

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

/** Complete empty-money graph expiry; real PostgreSQL and synthetic people/values only. */
class UnsubmittedCheckoutRetentionIT {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class LocalClient {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        MercadoPagoOrdersClient client(com.unicarona.payment.config.MercadoPagoSettings settings,ObjectMapper json){return new MercadoPagoOrdersClient(settings,json,java.net.URI.create("http://127.0.0.1:9"));}
    }
    @TempDir Path temporary;private static RealPostgres database;private ServletWebServerApplicationContext app;private JdbcTemplate sql;private String[] args;
    @BeforeAll static void database(){database=new RealPostgres();}
    @AfterAll static void databaseClose(){if(database!=null)database.close();}
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    @BeforeEach void setup()throws Exception {
        database.resetSchema();var values=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));values.removeIf(v->v.startsWith("--PAYMENT_PROVIDER="));values.addAll(List.of("--PAYMENT_PROVIDER=mercadopago","--MERCADO_PAGO_ACCESS_TOKEN=TEST-synthetic-retention-only","--MERCADO_PAGO_WEBHOOK_SECRET=Synthetic-retention-only-webhook-secret-2026","--MERCADO_PAGO_ENVIRONMENT=test","--PAYMENT_PROVIDER_TIMEOUT_MS=1000","--STORAGE_PATH="+temporary.resolve("files"),"--FRONTEND_PATH="+temporary.resolve("public"),"--PRIVACY_OPERATIONS_ROOT="+temporary.resolve("ops"),"--PRIVACY_ERASURE_JOURNAL_PATH="+temporary.resolve("journal"),"--DEV_MAIL_PATH="+temporary.resolve("mail")));args=values.toArray(String[]::new);
        var env=new MockEnvironment();for(String arg:args){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,Clock.systemUTC()).initialize();start();
    }
    private void start(){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,LocalClient.class).run(args);sql=app.getBean(JdbcTemplate.class);}
    private UUID owner(){UUID id=UUID.randomUUID();sql.update("INSERT INTO users(id,email,password_hash,name,birth_date,email_verified_at) VALUES(?,?,'synthetic-not-a-login','Synthetic checkout owner','2000-01-01',now())",id,id+"@example.test");return id;}
    private record Quote(UUID id,UUID payment,UUID passenger,UUID driver,UUID ride){}
    private Quote quote(int age,boolean payment){
        UUID passenger=owner(),driver=owner(),university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES(?,'Synthetic University','Gama','DF') RETURNING id",UUID.class,"synthetic-"+UUID.randomUUID());
        UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic','Black','ABC1D23',4,'approved') RETURNING id",UUID.class,driver),route=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Synthetic region','Synthetic meeting',10000) RETURNING id",UUID.class,university,driver);
        UUID pricing=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,0) RETURNING id",UUID.class,university);
        UUID ride=sql.queryForObject("INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,'ida',current_date-?,'18:30',4,100,1000,?) RETURNING id",UUID.class,university,route,driver,vehicle,age,pricing);
        UUID request=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,preview,reason,expires_at,created_at,updated_at) VALUES(?,?,?,'avulsa','accepted',jsonb_build_object('classId',?::text),'Synthetic obsolete negotiation',now()-?*interval '24 hours',now()-(?+20)*interval '24 hours',now()-?*interval '24 hours') RETURNING id",UUID.class,passenger,driver,ride,UUID.randomUUID().toString(),age,age,age);
        sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,100,1000)",request,ride);
        UUID id=payment?sql.queryForObject("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,expires_at,created_at) VALUES(?,?,100,'mercadopago','awaiting',now()-?*interval '24 hours',now()-(?+10)*interval '24 hours') RETURNING id",UUID.class,request,passenger,age,age):null;
        return new Quote(request,id,passenger,driver,ride);
    }
    private void prune()throws Exception{app.getBean(PrivacyUnsubmittedCheckoutMaintenance.class).runOnce(100,()->false);}
    private boolean exists(Quote q){return sql.queryForObject("SELECT EXISTS(SELECT 1 FROM ride_requests WHERE id=?)",Boolean.class,q.id());}
    private void card(Quote q,boolean started){sql.update("INSERT INTO mercado_pago_card_commands(payment_id,body_hash,status,payload_ciphertext,created_at,started_at) VALUES(?,?,'failed',NULL,now()-interval '120 days',CASE WHEN ? THEN now()-interval '120 days' END)",q.payment(),"a".repeat(64),started);}
    private UUID hold(Quote q,UUID owner){UUID id=UUID.randomUUID();String reason=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.HOLD_REASON,owner,id,"Synthetic case preservation");app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.update("INSERT INTO privacy_legal_holds(id,user_id,category,resource_type,legal_basis,reason_ciphertext,created_by,review_at,expires_at) VALUES(?,?,'financial','user','regular_exercise_of_rights',?,?,now()+interval '1 day',now()+interval '2 days')",id,owner,reason,owner);app.getBean(PrivacyHoldRecovery.class).stage();});return id;}
    private void restore(Path archive)throws Exception{app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(archive);start();}
    @Test void expiresTheEntireNeverFundedGraphIncludingOnlyClassifiedRequestEvents()throws Exception {
        var q=quote(120,true);sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) VALUES(?,'request_created','request',?,now()-interval '140 days'),(NULL,'request_expired','request',?,now())",q.passenger(),q.id(),q.id());sql.update("INSERT INTO notifications(user_id,title,body,route,ref_id) VALUES(?,'Pedido expirado','Synthetic expired notice','requests',?)",q.passenger(),q.id());
        prune();assertFalse(exists(q));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE id=?",Integer.class,q.payment()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM request_lines WHERE request_id=?",Integer.class,q.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_type='request' AND resource_id=?",Integer.class,q.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM notifications WHERE ref_id=?",Integer.class,q.id()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rides WHERE id=?",Integer.class,q.ride()));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void monthlyAcademicQuoteExpiresWithoutDeletingTheOfferedRoutine()throws Exception {
        var q=quote(120,true);var ride=sql.queryForMap("SELECT university_id,route_id,vehicle_id,pricing_version_id FROM rides WHERE id=?",q.ride());
        UUID course=sql.queryForObject("INSERT INTO courses(university_id,slug,name) VALUES(?,'synthetic-monthly','Synthetic course') RETURNING id",UUID.class,ride.get("university_id")),group=sql.queryForObject("INSERT INTO classes(university_id,course_id,period_number,label) VALUES(?,?,1,'A') RETURNING id",UUID.class,ride.get("university_id"),course);
        UUID routine=sql.queryForObject("INSERT INTO routines(route_id,driver_id,university_id,vehicle_id,class_id,weekdays,ida_time,ida_cents,capacity,pricing_version_id) SELECT route_id,driver_id,university_id,vehicle_id,?,ARRAY[extract(dow FROM ride_date)::smallint],'18:30',100,4,pricing_version_id FROM rides WHERE id=? RETURNING id",UUID.class,group,q.ride());
        sql.update("UPDATE rides SET routine_id=? WHERE id=?",routine,q.ride());sql.update("UPDATE ride_requests SET type='mensal',ride_id=NULL,routine_id=?,preview=jsonb_build_object('routineId',?::text,'driverId',?::text,'classId',?::text,'originalCents',100,'totalCents',100,'discount',0,'lines',jsonb_build_array(jsonb_build_object('rideId',?::text,'cents',100)),'conflicts','[]'::jsonb) WHERE id=?",routine,routine,q.driver(),group,q.ride(),q.id());
        prune();assertFalse(exists(q));assertEquals(0,sql.queryForObject("SELECT count(*) FROM payments WHERE id=?",Integer.class,q.payment()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM routines WHERE id=?",Integer.class,routine));assertEquals(1,sql.queryForObject("SELECT count(*) FROM rides WHERE id=?",Integer.class,q.ride()));
    }
    @Test void unpaidNegotiationWithoutAPaymentAlsoExpires()throws Exception {var q=quote(120,false);prune();assertFalse(exists(q));}
    @Test void recentNegotiationCannotBeDeletedEvenDirectly()throws Exception {var q=quote(89,true);prune();assertTrue(exists(q));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM payments WHERE id=?",q.payment()));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM ride_requests WHERE id=?",q.id()));}
    @Test void failedCommandsProvenNeverStartedExpireWithTheirEmptyPayment()throws Exception {
        var q=quote(120,true);card(q,false);sql.update("INSERT INTO mercado_pago_pix_commands(payment_id,amount_cents,payer_email,expiration_seconds,status,created_at) VALUES(?,100,NULL,1800,'failed',now()-interval '120 days')",q.payment());prune();assertFalse(exists(q));assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,q.payment()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM mercado_pago_pix_commands WHERE payment_id=?",Integer.class,q.payment()));
    }
    @Test void preparedButNeverSubmittedDistributionIsNotMistakenForReceivedMoney()throws Exception {
        var q=quote(120,true);app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->app.getBean(LedgerService.class).post(LedgerCommand.transfer("synthetic-preparation-credit:"+UUID.randomUUID(),"synthetic_credit","Synthetic still available credit",List.of(new LedgerCommand.Posting(null,AccountKind.PROVIDER_CLEARING,-40),new LedgerCommand.Posting(q.passenger(),AccountKind.PASSENGER_CREDIT,40)))));
        sql.update("UPDATE payments SET status='pending',method='card',credit_cents=40,external_cents=60,credit_requested=true WHERE id=?",q.payment());card(q,false);prune();assertFalse(exists(q));assertEquals(40,app.getBean(LedgerService.class).balance(q.passenger(),AccountKind.PASSENGER_CREDIT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void anUnknownExternalOutcomeIsNeverTreatedAsANeverSubmittedPayment()throws Exception {
        var q=quote(120,true);card(q,true);prune();assertTrue(exists(q));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_card_commands SET started_at=NULL WHERE payment_id=?",q.payment()));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM mercado_pago_card_commands WHERE payment_id=?",q.payment()));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM payments WHERE id=?",q.payment()));
    }
    @Test void pixAndDebtSubmissionMarkersCannotBeClearedOrMovedToANewClock()throws Exception {
        var q=quote(120,true);sql.update("INSERT INTO mercado_pago_pix_commands(payment_id,amount_cents,payer_email,expiration_seconds,status,created_at,started_at) VALUES(?,100,NULL,1800,'failed',now()-interval '120 days',now()-interval '120 days')",q.payment());assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_pix_commands SET started_at=NULL WHERE payment_id=?",q.payment()));
        UUID debt=sql.queryForObject("INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email,state,started_at) VALUES(?,100,'synthetic-payer@example.test','EXPIRED',now()-interval '120 days') RETURNING id",UUID.class,q.passenger());assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE passenger_debt_payments SET started_at=now() WHERE id=?",debt));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE passenger_debt_payments SET started_at=NULL WHERE id=?",debt));prune();assertTrue(exists(q));
    }
    @Test void aLegacyAttemptCountWithoutAStartMarkerIsNeitherErasedNorResubmitted()throws Exception {
        var q=quote(120,true);sql.update("UPDATE payments SET status='pending',external_cents=100 WHERE id=?",q.payment());sql.update("INSERT INTO mercado_pago_card_commands(payment_id,body_hash,payload_ciphertext,status,attempts,created_at) VALUES(?,?,'synthetic-unknown-input','pending',2,now()-interval '120 days')",q.payment(),"a".repeat(64));sql.update("INSERT INTO mercado_pago_pix_commands(payment_id,amount_cents,payer_email,expiration_seconds,status,attempts,created_at) VALUES(?,100,'synthetic-unknown@example.test',1800,'pending',2,now()-interval '120 days')",q.payment());
        var transientRetention=app.getBean(PrivacyCheckoutRetention.class);assertFalse(transientRetention.retire(new PrivacyCheckoutRetention.Candidate("card",q.payment(),q.passenger()),false));assertFalse(transientRetention.retire(new PrivacyCheckoutRetention.Candidate("pix",q.payment(),q.passenger()),false));
        var gateway=app.getBean(com.unicarona.payment.service.MercadoPagoTransactions.class);assertEquals("PAYMENT_COMMAND_SUBMISSION_UNKNOWN",assertThrows(com.unicarona.shared.ApiException.class,()->gateway.cardStarted(q.payment())).code());assertEquals("PAYMENT_COMMAND_SUBMISSION_UNKNOWN",assertThrows(com.unicarona.shared.ApiException.class,()->gateway.pixStarted(q.payment())).code());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_card_commands SET attempts=0 WHERE payment_id=?",q.payment()));prune();assertTrue(exists(q));assertEquals("synthetic-unknown-input",sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,q.payment()));assertEquals("synthetic-unknown@example.test",sql.queryForObject("SELECT payer_email FROM mercado_pago_pix_commands WHERE payment_id=?",String.class,q.payment()));
    }
    @Test void anExternalOrderEvenRejectedKeepsItsFinancialHistory()throws Exception {
        var q=quote(120,true);sql.update("INSERT INTO mercado_pago_orders(payment_id,order_id,state,provider_status,status_detail) VALUES(?,?,'REJECTED','failed','rejected')",q.payment(),"ORDTST"+UUID.randomUUID().toString().replace("-",""));prune();assertTrue(exists(q));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM payments WHERE id=?",q.payment()));
    }
    @Test void unknownOrMisattributedEventsFailClosedWithoutRemovingAnyPart()throws Exception {
        var q=quote(120,true);sql.update("INSERT INTO audit_logs(action,resource_type,resource_id,created_at) VALUES('request_created','request',?,now()-interval '120 days')",q.id());prune();assertTrue(exists(q));assertEquals(1,sql.queryForObject("SELECT count(*) FROM payments WHERE id=?",Integer.class,q.payment()));
        var other=quote(120,true);sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) VALUES(?,'new_unclassified_lifecycle','request',?,now()-interval '120 days')",other.passenger(),other.id());prune();assertTrue(exists(other));
    }
    @Test void financialCaseForEitherParticipantWinsButAnUnrelatedCaseDoesNot()throws Exception {
        var held=quote(120,true);var unrelated=quote(120,true);hold(held,held.driver());hold(unrelated,owner());prune();assertTrue(exists(held));assertFalse(exists(unrelated));
    }
    @Test void aPostBackupFinancialHoldIsReplayedBeforeAbandonedCheckoutExpiry()throws Exception {
        var q=quote(120,true);Path archive=temporary.resolve("before-financial-hold.dump");database.dumpDatabaseArchive(archive);UUID hold=hold(q,q.passenger());restore(archive);assertTrue(exists(q));assertTrue(app.getBean(PrivacyReadiness.class).ready());assertEquals(1,sql.queryForObject("SELECT count(*) FROM privacy_legal_holds WHERE id=?",Integer.class,hold));
        app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.update("UPDATE privacy_legal_holds SET released_at=now() WHERE id=?",hold);app.getBean(PrivacyHoldRecovery.class).stage();});prune();assertFalse(exists(q));
    }
    @Test void restoringAnExpiredQuoteDoesNotCreateAFreshRetentionWindow()throws Exception {
        var q=quote(120,true);var deadline=sql.queryForObject("SELECT privacy_unsubmitted_request_deadline(q) FROM ride_requests q WHERE id=?",java.sql.Timestamp.class,q.id());Path archive=temporary.resolve("expired-empty-checkout.dump");database.dumpDatabaseArchive(archive);prune();assertFalse(exists(q));restore(archive);assertTrue(app.getBean(PrivacyReadiness.class).ready());assertFalse(exists(q));assertTrue(deadline.before(new java.sql.Timestamp(System.currentTimeMillis())));
    }
    @Test void unrelatedLiveCreditRemainsAvailableWhileTheAbandonedQuoteExpires()throws Exception {
        var q=quote(120,true);app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->app.getBean(LedgerService.class).post(LedgerCommand.transfer("synthetic-unrelated-credit:"+UUID.randomUUID(),"synthetic_credit","Synthetic unrelated available credit",List.of(new LedgerCommand.Posting(null,AccountKind.PROVIDER_CLEARING,-700),new LedgerCommand.Posting(q.passenger(),AccountKind.PASSENGER_CREDIT,700)))));prune();assertFalse(exists(q));assertEquals(700,app.getBean(LedgerService.class).balance(q.passenger(),AccountKind.PASSENGER_CREDIT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
    @Test void expiredCachedResponsesAreRemovedButALiveResponseStillProtectsItsBoundary()throws Exception {
        var q=quote(120,true);sql.update("INSERT INTO idempotency_keys(actor_id,key,scope,body_hash,response,expires_at) VALUES(?,'synthetic-live-key-0001','payment:checkout',?,jsonb_build_object('paymentId',upper(?::text)),now()+interval '1 day')",q.passenger(),"a".repeat(64),q.payment());
        sql.update("INSERT INTO idempotency_keys(actor_id,key,scope,body_hash,response,expires_at) VALUES(?,'synthetic-old-request','request:create',?,jsonb_build_object('id',upper(?::text)),now()-interval '1 day'),(?,'synthetic-old-prepared','payment:checkout',?,jsonb_build_object('paymentId',?::text),now()-interval '1 day'),(?,'synthetic-old-card','mercadopago:card',?,to_jsonb(upper(?::text)),now()-interval '1 day')",q.passenger(),"a".repeat(64),q.id(),q.passenger(),"a".repeat(64),q.payment(),q.passenger(),"a".repeat(64),q.payment());
        prune();assertTrue(exists(q));var original=sql.queryForObject("SELECT privacy_unsubmitted_request_deadline(q) FROM ride_requests q WHERE id=?",java.sql.Timestamp.class,q.id());sql.update("UPDATE idempotency_keys SET expires_at=now()-interval '1 day' WHERE actor_id=?",q.passenger());prune();assertFalse(exists(q));assertEquals(0,sql.queryForObject("SELECT count(*) FROM idempotency_keys WHERE actor_id=?",Integer.class,q.passenger()));assertTrue(original.before(new java.sql.Timestamp(System.currentTimeMillis())));
    }
    @Test void anUnknownFutureForeignKeyRollsBackTheWholeRetirementGraph()throws Exception {
        var q=quote(120,true);card(q,false);sql.update("INSERT INTO audit_logs(actor_id,action,resource_type,resource_id,created_at) VALUES(?,'request_created','request',?,now()-interval '140 days')",q.passenger(),q.id());sql.execute("CREATE TABLE synthetic_additional_financial_dependency(payment_id uuid REFERENCES payments)");sql.update("INSERT INTO synthetic_additional_financial_dependency VALUES(?)",q.payment());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,this::prune);assertTrue(exists(q));assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,q.payment()));assertEquals(1,sql.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id=?",Integer.class,q.id()));
    }
    @Test void anObservedCommandWitnessCannotBeDiscardedByStrippingItsRecordedPurpose()throws Exception {
        var q=quote(120,true);card(q,false);UUID copy=UUID.randomUUID();String cipher=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,q.passenger(),copy,"{\"name\":\"Synthetic financial claimant\",\"email\":\"claimant@example.test\",\"phone\":null}");sql.update("INSERT INTO privacy_retained_records(id,user_id,category,resource_type,resource_id,content_ciphertext,reason,legal_basis,delete_after) VALUES(?,?,'contract_identity','user',?,?,'Synthetic retained claim','regular_exercise_of_rights',now()+interval '5 years')",copy,q.passenger(),q.passenger(),cipher);
        app.getBean(PrivacyRetentionTransactions.class).prune(100);prune();assertTrue(exists(q));long sequence=app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().head().sequence();
        sql.update("UPDATE privacy_retained_records SET financial_purpose=jsonb_set(financial_purpose,'{facts}','[]') WHERE id=?",copy);assertThrows(Exception.class,()->app.getBean(PrivacyUnsubmittedCheckoutRetention.class).retire(new PrivacyUnsubmittedCheckoutRetention.Candidate(q.id(),q.passenger(),q.driver()),false));assertTrue(exists(q));assertEquals(1,sql.queryForObject("SELECT count(*) FROM mercado_pago_card_commands WHERE payment_id=?",Integer.class,q.payment()));assertEquals(sequence,app.getBean(PrivacyHoldControlJournal.class).current().orElseThrow().head().sequence());assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
    @Test void aBusyOwnerDoesNotStarveAnUnrelatedQuoteAndIsRetriedAfterUnlock()throws Exception {
        var busy=quote(120,true);var free=quote(120,true);
        try(var connection=database.dataSource().getConnection()){connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")){lock.setObject(1,busy.passenger());lock.executeQuery().close();}prune();assertTrue(exists(busy));assertFalse(exists(free));connection.rollback();}
        prune();assertFalse(exists(busy));
    }
    @Test void activeDeliveryLeaseProtectsTheGraphAndAnExpiredLeaseIsRetiredWithItsJob()throws Exception {
        var q=quote(120,true);card(q,false);UUID job=sql.queryForObject("INSERT INTO outbox_jobs(type,event_key,payload,status,leased_until) VALUES('mercadopago_card',?,jsonb_build_object('paymentId',?::text),'processing',now()+interval '1 day') RETURNING id",UUID.class,"mercadopago_card:"+q.payment(),q.payment());prune();assertTrue(exists(q));assertEquals(1,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE id=?",Integer.class,job));sql.update("UPDATE outbox_jobs SET leased_until=now()-interval '1 day' WHERE id=?",job);prune();assertFalse(exists(q));assertEquals(0,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE id=?",Integer.class,job));
    }
    @Test void anUnclassifiedDeliveryReferencingThePaymentCannotBeSilentlyDiscarded()throws Exception {
        var q=quote(120,true);UUID job=sql.queryForObject("INSERT INTO outbox_jobs(type,event_key,payload) VALUES('new_financial_delivery',?,jsonb_build_object('paymentId',?::text)) RETURNING id",UUID.class,"synthetic-future-delivery:"+UUID.randomUUID(),q.payment());prune();assertTrue(exists(q));assertEquals(1,sql.queryForObject("SELECT count(*) FROM outbox_jobs WHERE id=?",Integer.class,job));
    }
    @Test void aDurableUnprocessedWebhookIsFinancialEvidenceEvenBeforeAnOrderWasAttached()throws Exception {
        var q=quote(120,true);app.getBean(com.unicarona.payment.service.WebhookReceipts.class).record("mercadopago","synthetic-observation:"+UUID.randomUUID(),Map.of("payment",q.payment(),"state","PENDING","version",1));var other=quote(120,true);app.getBean(com.unicarona.payment.service.WebhookReceipts.class).record("development","synthetic-other-observation:"+UUID.randomUUID(),Map.of("paymentId",other.payment().toString().toUpperCase(Locale.ROOT),"status","failed"));prune();assertTrue(exists(q));assertTrue(exists(other));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM payments WHERE id=?",q.payment()));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM payments WHERE id=?",other.payment()));assertEquals(0,app.getBean(LedgerService.class).balance(q.passenger(),AccountKind.PASSENGER_CREDIT));
    }
    @Test void aPendingDataAccessRequestKeepsTheDataButAnErasureRequestDoesNotFreezeDeletion()throws Exception {
        var access=quote(120,true);var erasure=quote(120,true);UUID request=UUID.randomUUID();String body=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,access.passenger(),request,"Synthetic access to still available quote data");sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,due_at) VALUES(?,?,'access',?,now()+interval '15 days')",request,access.passenger(),body);UUID other=UUID.randomUUID();String otherBody=app.getBean(PrivacyEvidenceCrypto.class).seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,erasure.passenger(),other,"Synthetic erasure request");sql.update("INSERT INTO privacy_requests(id,user_id,kind,details_ciphertext,due_at) VALUES(?,?,'erasure',?,now()+interval '15 days')",other,erasure.passenger(),otherBody);
        prune();assertTrue(exists(access));assertFalse(exists(erasure));sql.update("UPDATE privacy_requests SET status='completed',resolved_at=now() WHERE id=?",request);prune();assertFalse(exists(access));
    }
    @Test void aRealBalancedReceiptIsPreservedAndCannotBeDeletedDirectly()throws Exception {
        var q=quote(120,true);app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.update("UPDATE payments SET status='pending',external_cents=100 WHERE id=?",q.payment());app.getBean(LedgerService.class).post(LedgerCommand.receiptCredit(q.payment(),"Synthetic late actual receipt",List.of(new LedgerCommand.Posting(null,AccountKind.PROVIDER_CLEARING,-100),new LedgerCommand.Posting(q.passenger(),AccountKind.PASSENGER_CREDIT,100))));sql.update("UPDATE payments SET status='approved',paid_at=now() WHERE id=?",q.payment());});prune();assertTrue(exists(q));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("DELETE FROM payments WHERE id=?",q.payment()));assertEquals(100,app.getBean(LedgerService.class).balance(q.passenger(),AccountKind.PASSENGER_CREDIT));assertEquals(0,app.getBean(LedgerService.class).reconciliation().difference());
    }
}
