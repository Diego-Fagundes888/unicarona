package com.unicarona.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.UniCaronaApplication;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.payment.service.*;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.*;
import com.unicarona.support.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL/transactions, local-only client and physical archive recovery.
 * Tests do not submit payments, send mail or use real credentials. */
class CheckoutDataRetentionIT {
    @TempDir Path temporary;private static RealPostgres database;private ServletWebServerApplicationContext app;private JdbcTemplate sql;private String[] args;
    @TestConfiguration static class LocalClient {@Bean @Primary MercadoPagoOrdersClient client(MercadoPagoSettings settings,ObjectMapper json){return new MercadoPagoOrdersClient(settings,json,URI.create("http://127.0.0.1:9"));}}
    @BeforeAll static void database(){database=new RealPostgres();}
    @AfterAll static void databaseClose(){if(database!=null)database.close();}
    @AfterEach void close(){if(app!=null){app.close();app=null;}}
    @BeforeEach void setup()throws Exception {
        database.resetSchema();var values=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));values.removeIf(v->v.startsWith("--PAYMENT_PROVIDER="));values.addAll(List.of("--PAYMENT_PROVIDER=mercadopago","--MERCADO_PAGO_ACCESS_TOKEN=TEST-synthetic-checkout-only","--MERCADO_PAGO_WEBHOOK_SECRET=Synthetic-checkout-only-webhook-secret-2026","--MERCADO_PAGO_ENVIRONMENT=test","--PAYMENT_PROVIDER_TIMEOUT_MS=1000","--STORAGE_PATH="+temporary.resolve("files"),"--FRONTEND_PATH="+temporary.resolve("public"),"--PRIVACY_OPERATIONS_ROOT="+temporary.resolve("ops"),"--PRIVACY_ERASURE_JOURNAL_PATH="+temporary.resolve("journal"),"--DEV_MAIL_PATH="+temporary.resolve("mail")));args=values.toArray(String[]::new);
        var env=new MockEnvironment();for(String arg:args){int at=arg.indexOf('=');env.withProperty(arg.substring(2,at),arg.substring(at+1));}var json=new ObjectMapper();var crypto=new CryptoService();var auth=new AuthSettings(env);var files=new StorageSettings(env);new PrivacyBackupCatalog(new PrivacyOperationsSettings(env,files),new PrivacySettings(env),new PrivacySigningKeys(env,auth,crypto,json),json,Clock.systemUTC()).initialize();start();
    }
    private void start(){app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(UniCaronaApplication.class,LocalClient.class).run(args);sql=app.getBean(JdbcTemplate.class);}
    private Actor owner(){UUID id=sql.queryForObject("INSERT INTO users(email,password_hash,name,birth_date,email_verified_at) VALUES(?,'synthetic-test-hash','Synthetic checkout owner','2000-01-01',now()) RETURNING id",UUID.class,UUID.randomUUID()+"@example.test");return new Actor(id,"user","active",null,null,null,true,Set.of());}
    private UUID payment(Actor owner,String method){
        var driver=owner();
        UUID university=sql.queryForObject("INSERT INTO universities(slug,name,city,state_code) VALUES(?,'Synthetic campus','Synthetic city','GO') RETURNING id",UUID.class,"checkout-"+UUID.randomUUID());
        UUID vehicle=sql.queryForObject("INSERT INTO vehicles(owner_id,model,color,plate,capacity,status) VALUES(?,'Synthetic vehicle','Black','ABC1D23',4,'approved') RETURNING id",UUID.class,driver.id());
        UUID route=sql.queryForObject("INSERT INTO ride_routes(university_id,driver_id,region,meeting,distance_m) VALUES(?,?,'Synthetic region','Synthetic meeting',12000) RETURNING id",UUID.class,university,driver.id());
        UUID price=sql.queryForObject("INSERT INTO pricing_versions(university_id,fuel_cents,efficiency,wear_cents,reference_passengers,commission_bps,discount_bps) VALUES(?,600,10,10,3,1000,0) RETURNING id",UUID.class,university);
        UUID ride=sql.queryForObject("INSERT INTO rides(university_id,route_id,driver_id,vehicle_id,direction,ride_date,departure_time,capacity,price_cents,commission_bps,pricing_version_id) VALUES(?,?,?,?,'ida',current_date+3,'18:00',4,1000,1000,?) RETURNING id",UUID.class,university,route,driver.id(),vehicle,price);
        UUID request=sql.queryForObject("INSERT INTO ride_requests(passenger_id,driver_id,ride_id,type,status,expires_at) VALUES(?,?,?,'avulsa','accepted',now()+interval '30 minutes') RETURNING id",UUID.class,owner.id(),driver.id(),ride);
        sql.update("INSERT INTO request_lines(request_id,ride_id,cents,commission_bps) VALUES(?,?,1000,1000)",request,ride);
        UUID payment=sql.queryForObject("INSERT INTO payments(request_id,passenger_id,amount_cents,provider,status,method,expires_at) VALUES(?,?,1000,'mercadopago','awaiting',?,now()+interval '30 minutes') RETURNING id",UUID.class,request,owner.id(),method);
        sql.update("UPDATE payments SET status='pending',external_cents=amount_cents WHERE id=?",payment);return payment;
    }
    private void order(UUID payment,String state,String detail){sql.update("INSERT INTO mercado_pago_orders(payment_id,order_id,state,provider_status,status_detail,qr_code,qr_base64,ticket_url,challenge_url) VALUES(?,?,?,?,?,'synthetic-qr','synthetic-base64','https://www.mercadopago.com.br/synthetic-ticket','https://www.mercadopago.com.br/synthetic-challenge')",payment,"ORDTST"+payment.toString().replace("-",""),state,"action_required",detail);}
    private void pix(UUID payment){sql.update("INSERT INTO mercado_pago_pix_commands(payment_id,amount_cents,payer_email,expiration_seconds) SELECT id,external_cents,'payer@example.test',1800 FROM payments WHERE id=?",payment);}
    private void card(UUID payment){String body=app.getBean(CryptoService.class).encrypt("{\"card\":{\"token\":\"synthetic-single-use-token-only\"}}",app.getBean(AuthSettings.class).encryptionKey());sql.update("INSERT INTO mercado_pago_card_commands(payment_id,body_hash,payload_ciphertext) VALUES(?,?,?)",payment,"a".repeat(64),body);}
    private UUID debt(Actor owner){return debt(owner,false);}
    private UUID debt(Actor owner,boolean expired){return sql.queryForObject("INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email,expires_at) VALUES(?,1000,'debt-payer@example.test',CASE WHEN ? THEN clock_timestamp()-interval '1 second' ELSE clock_timestamp()+interval '30 minutes' END) RETURNING id",UUID.class,owner.id(),expired);}
    private PrivacyCheckoutRetention tx(){return app.getBean(PrivacyCheckoutRetention.class);}
    private boolean retire(String kind,UUID id,Actor owner)throws Exception{return tx().retire(new PrivacyCheckoutRetention.Candidate(kind,id,owner.id()),true);}
    private void guard()throws Exception{app.getBean(PrivacyRestoreGuard.class).run(new DefaultApplicationArguments());}
    @Test void livePixRemainsUsableAndUnusedTicketAndCardChallengeAreNeverStored()throws Exception {
        var owner=owner();UUID id=payment(owner,"pix");order(id,"PENDING","waiting_transfer");assertNull(sql.queryForObject("SELECT ticket_url FROM mercado_pago_orders WHERE payment_id=?",String.class,id));assertNull(sql.queryForObject("SELECT challenge_url FROM mercado_pago_orders WHERE payment_id=?",String.class,id));var detail=app.getBean(MercadoPagoService.class).detail(owner,id);assertEquals("synthetic-qr",detail.qrCode());assertEquals("synthetic-base64",detail.qrBase64());assertNull(detail.ticketUrl());assertFalse(retire("order",id,owner));assertEquals("PAYMENT_NOT_FOUND",assertThrows(com.unicarona.shared.ApiException.class,()->app.getBean(MercadoPagoService.class).detail(owner(),id)).code());
    }
    @Test void expiryHidesCheckoutBeforeWorkerAndRemovalDoesNotChangeMoneyOrLifecycle()throws Exception {
        var owner=owner();UUID id=payment(owner,"pix");order(id,"PENDING","waiting_transfer");sql.update("UPDATE payments SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);String paymentBefore=sql.queryForObject("SELECT to_jsonb(p)::text FROM payments p WHERE id=?",String.class,id);assertNotNull(sql.queryForObject("SELECT qr_code FROM mercado_pago_orders WHERE payment_id=?",String.class,id));assertNull(app.getBean(MercadoPagoService.class).detail(owner,id).qrCode());assertTrue(retire("order",id,owner));assertFalse(retire("order",id,owner));assertTrue(sql.queryForObject("SELECT qr_code IS NULL AND qr_base64 IS NULL AND state='PENDING' FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,id));assertEquals(paymentBefore,sql.queryForObject("SELECT to_jsonb(p)::text FROM payments p WHERE id=?",String.class,id));assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions",Integer.class));
    }
    @Test void completedOrdersCannotRepopulateCheckoutCredentialsOnLaterProviderUpdates()throws Exception {
        var owner=owner();UUID id=payment(owner,"pix");order(id,"APPROVED","accredited");sql.update("UPDATE mercado_pago_orders SET qr_code='again',qr_base64='again',ticket_url='https://www.mercadopago.com.br/again',challenge_url='https://www.mercadopago.com.br/again',provider_version=provider_version+1 WHERE payment_id=?",id);assertTrue(sql.queryForObject("SELECT qr_code IS NULL AND qr_base64 IS NULL AND ticket_url IS NULL AND challenge_url IS NULL FROM mercado_pago_orders WHERE payment_id=?",Boolean.class,id));assertNull(app.getBean(MercadoPagoService.class).detail(owner,id).qrCode());assertEquals("APPROVED",app.getBean(MercadoPagoService.class).detail(owner,id).state());
    }
    @Test void challengeHasOnlyItsActualCardStepPurpose()throws Exception {
        var owner=owner();UUID id=payment(owner,"card");order(id,"PENDING","pending_challenge");assertEquals("https://www.mercadopago.com.br/synthetic-challenge",app.getBean(MercadoPagoService.class).detail(owner,id).challengeUrl());assertNull(sql.queryForObject("SELECT qr_code FROM mercado_pago_orders WHERE payment_id=?",String.class,id));sql.update("UPDATE mercado_pago_orders SET status_detail='in_process' WHERE payment_id=?",id);assertNull(sql.queryForObject("SELECT challenge_url FROM mercado_pago_orders WHERE payment_id=?",String.class,id));assertNull(app.getBean(MercadoPagoService.class).detail(owner,id).challengeUrl());
    }
    @Test void successfulPixCommandDropsItsContactButPreservesItsImmutableIdentity()throws Exception {
        var owner=owner();UUID id=payment(owner,"pix");pix(id);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_pix_commands SET payer_email=NULL WHERE payment_id=?",id));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_pix_commands SET payer_email='other@example.test' WHERE payment_id=?",id));sql.update("UPDATE mercado_pago_pix_commands SET status='done' WHERE payment_id=?",id);assertNull(sql.queryForObject("SELECT payer_email FROM mercado_pago_pix_commands WHERE payment_id=?",String.class,id));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_pix_commands SET amount_cents=999 WHERE payment_id=?",id));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE mercado_pago_pix_commands SET payer_email='payer@example.test' WHERE payment_id=?",id));assertEquals(1000,sql.queryForObject("SELECT amount_cents FROM mercado_pago_pix_commands WHERE payment_id=?",Integer.class,id));
    }
    @Test void onlyNeverSubmittedExpiredCommandsLoseTheirRecoveryInputs()throws Exception {
        var owner=owner();UUID neverPix=payment(owner,"pix"),unknownPix=payment(owner,"pix"),neverCard=payment(owner,"card"),unknownCard=payment(owner,"card");pix(neverPix);pix(unknownPix);card(neverCard);card(unknownCard);sql.update("UPDATE mercado_pago_pix_commands SET started_at=now() WHERE payment_id=?",unknownPix);sql.update("UPDATE mercado_pago_card_commands SET started_at=now() WHERE payment_id=?",unknownCard);sql.update("UPDATE payments SET expires_at=clock_timestamp()-interval '1 second' WHERE passenger_id=?",owner.id());assertTrue(retire("pix",neverPix,owner));assertTrue(retire("card",neverCard,owner));assertFalse(retire("pix",unknownPix,owner));assertFalse(retire("card",unknownCard,owner));assertEquals("payer@example.test",sql.queryForObject("SELECT payer_email FROM mercado_pago_pix_commands WHERE payment_id=?",String.class,unknownPix));assertNotNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,unknownCard));assertEquals("failed",sql.queryForObject("SELECT status FROM mercado_pago_card_commands WHERE payment_id=?",String.class,neverCard));
    }
    @Test void knownOrderIdentityEndsCreationInputPurposeWithoutClaimingPaymentApproval()throws Exception {
        var owner=owner();UUID pix=payment(owner,"pix"),card=payment(owner,"card");pix(pix);card(card);for(UUID id:List.of(pix,card))sql.update("UPDATE payments SET provider_reference=?,checkout_url='http://localhost:39002/synthetic-checkout',gateway_environment='test' WHERE id=?","ORDTST"+id.toString().replace("-",""),id);assertTrue(retire("pix",pix,owner));assertTrue(retire("card",card,owner));assertNull(sql.queryForObject("SELECT payer_email FROM mercado_pago_pix_commands WHERE payment_id=?",String.class,pix));assertNull(sql.queryForObject("SELECT payload_ciphertext FROM mercado_pago_card_commands WHERE payment_id=?",String.class,card));assertEquals(2,sql.queryForObject("SELECT count(*) FROM payments WHERE passenger_id=? AND status='pending' AND paid_at IS NULL",Integer.class,owner.id()));assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_transactions",Integer.class));
    }
    @Test void debtRequestDiscardsCreationContactAfterIdentityAttachmentAndStillAcceptsLaterLifecycle()throws Exception {
        var owner=owner();UUID id=debt(owner);sql.update("UPDATE passenger_debt_payments SET order_id=?,state='PENDING',qr_code='debt-qr',qr_base64='debt-base64' WHERE id=?","ORDTST"+id.toString().replace("-",""),id);assertNull(sql.queryForObject("SELECT payer_email FROM passenger_debt_payments WHERE id=?",String.class,id));assertEquals("debt-qr",app.getBean(DebtPaymentTransactions.class).detail(owner.id(),id).qrCode());sql.update("UPDATE passenger_debt_payments SET state='UNDER_REVIEW',provider_version=2 WHERE id=?",id);assertNull(app.getBean(DebtPaymentTransactions.class).detail(owner.id(),id).qrCode());assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE passenger_debt_payments SET amount_cents=999 WHERE id=?",id));assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE passenger_debt_payments SET payer_email='again@example.test' WHERE id=?",id));assertEquals("UNDER_REVIEW",sql.queryForObject("SELECT state FROM passenger_debt_payments WHERE id=?",String.class,id));
    }
    @Test void expiredUnsentDebtCheckoutIsNotNewlySubmittedButUnknownSubmissionRemainsRecoverable()throws Exception {
        var owner=owner();UUID never=debt(owner,true);assertTrue(app.getBean(DebtPaymentTransactions.class).start(never).isEmpty());assertEquals("EXPIRED",sql.queryForObject("SELECT state FROM passenger_debt_payments WHERE id=?",String.class,never));assertNull(sql.queryForObject("SELECT payer_email FROM passenger_debt_payments WHERE id=?",String.class,never));UUID unknown=debt(owner,true);sql.update("UPDATE passenger_debt_payments SET started_at=now() WHERE id=?",unknown);assertFalse(retire("debt",unknown,owner));assertEquals("debt-payer@example.test",app.getBean(DebtPaymentTransactions.class).start(unknown).orElseThrow().email());
    }
    @Test void debtQrExpiresBeforeWorkerWithoutRewritingVerifiedAmounts()throws Exception {
        var owner=owner();UUID id=sql.queryForObject("INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email,expires_at) VALUES(?,1000,'debt-payer@example.test',clock_timestamp()+interval '3 seconds') RETURNING id",UUID.class,owner.id());sql.update("UPDATE passenger_debt_payments SET order_id=?,state='PENDING',qr_code='debt-qr' WHERE id=?","ORDTST"+id.toString().replace("-",""),id);Timestamp deadline=sql.queryForObject("SELECT expires_at FROM passenger_debt_payments WHERE id=?",Timestamp.class,id);long wait=Duration.between(Instant.now(),deadline.toInstant().plusMillis(100)).toMillis();assertTrue(wait<8000);if(wait>0)Thread.sleep(wait);assertNull(app.getBean(DebtPaymentTransactions.class).detail(owner.id(),id).qrCode());assertTrue(retire("debt",id,owner));assertEquals(1000,sql.queryForObject("SELECT amount_cents FROM passenger_debt_payments WHERE id=?",Integer.class,id));assertEquals("PENDING",sql.queryForObject("SELECT state FROM passenger_debt_payments WHERE id=?",String.class,id));
    }
    @Test void physicalArchiveRestoreCannotRenewAnOriginalCheckoutDeadline()throws Exception {
        var owner=owner();UUID id=payment(owner,"pix");order(id,"PENDING","waiting_transfer");sql.update("UPDATE payments SET expires_at=clock_timestamp()+interval '3 seconds' WHERE id=?",id);Timestamp deadline=sql.queryForObject("SELECT expires_at FROM payments WHERE id=?",Timestamp.class,id);Path archive=temporary.resolve("before-checkout-expiration.dump");database.dumpDatabaseArchive(archive);long wait=Duration.between(Instant.now(),deadline.toInstant().plusMillis(100)).toMillis();assertTrue(wait<8000);if(wait>0)Thread.sleep(wait);assertTrue(retire("order",id,owner));app.close();app=null;database.resetSchema();database.restoreDatabaseArchive(archive);start();assertTrue(app.getBean(PrivacyReadiness.class).ready());assertNull(sql.queryForObject("SELECT qr_code FROM mercado_pago_orders WHERE payment_id=?",String.class,id));assertEquals(deadline,sql.queryForObject("SELECT expires_at FROM payments WHERE id=?",Timestamp.class,id));assertEquals("PENDING",sql.queryForObject("SELECT state FROM mercado_pago_orders WHERE payment_id=?",String.class,id));
    }
    @Test void ownerContentionDoesNotStarveAnotherExpiredCheckout()throws Exception {
        for(var owner:List.of(owner(),owner())){UUID id=payment(owner,"pix");order(id,"PENDING","waiting_transfer");sql.update("UPDATE payments SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);}var first=tx().candidates(null,null,1).getFirst();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var writer=executor.submit(()->app.getBean(TransactionTemplate.class).executeWithoutResult(ignored->{sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",first.owner());locked.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IllegalStateException(stop);}}));try{assertTrue(locked.await(8,TimeUnit.SECONDS));var task=app.getBean(PrivacyCheckoutMaintenance.class);assertEquals(0,task.runOnce(1,()->false));assertEquals(1,task.runOnce(1,()->false));assertNotNull(sql.queryForObject("SELECT qr_code FROM mercado_pago_orders WHERE payment_id=?",String.class,first.id()));}finally{release.countDown();}writer.get(8,TimeUnit.SECONDS);}finally{release.countDown();}
    }
    @Test void unconfirmedIndependentControlBlocksCheckoutCleanup()throws Exception {
        var owner=owner();UUID id=payment(owner,"pix");order(id,"PENDING","waiting_transfer");sql.update("UPDATE payments SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);app.getBean(PrivacyHoldControlJournal.class).prepare(app.getBean(ObjectMapper.class).createObjectNode().put("syntheticUnconfirmedPreparation",true));assertThrows(java.io.IOException.class,()->retire("order",id,owner));assertNotNull(sql.queryForObject("SELECT qr_code FROM mercado_pago_orders WHERE payment_id=?",String.class,id));assertFalse(app.getBean(PrivacyReadiness.class).ready());
    }
}
