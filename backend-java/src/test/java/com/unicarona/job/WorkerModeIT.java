package com.unicarona.job;

import com.unicarona.UniCaronaApplication;
import com.unicarona.job.service.WorkerLifecycle;
import com.unicarona.notification.provider.EmailProvider;
import com.unicarona.security.CryptoService;
import com.unicarona.support.*;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Real non-HTTP worker contexts and real PostgreSQL; the external email boundary is synthetic. */
class WorkerModeIT {
    private static RealPostgres database;
    private static ConfigurableApplicationContext api;
    private static JdbcTemplate sql;
    private static CryptoService crypto;
    private ConfigurableApplicationContext worker;
    @TempDir Path configuration;
    @TestConfiguration(proxyBeanMethods=false) static class ProviderConfiguration {
        @Bean TrackingEmail trackingEmail() { return new TrackingEmail(); }
    }
    static final class TrackingEmail implements EmailProvider {
        final AtomicInteger calls=new AtomicInteger();
        static volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
        @Override public void send(Message input) throws Exception { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS)); }
    }
    @BeforeAll static void start() throws Exception {
        database=new RealPostgres();database.resetSchema();api=UniCaronaApplication.application().run(AuthTestSettings.arguments(database.apiConnectionUrl()));sql=api.getBean(JdbcTemplate.class);crypto=api.getBean(CryptoService.class);
    }
    @BeforeEach void prepare() { sql.update("DELETE FROM outbox_jobs");TrackingEmail.entered=new CountDownLatch(1);TrackingEmail.release=new CountDownLatch(0); }
    @AfterEach void stopWorker() { TrackingEmail.release.countDown();if(worker!=null) worker.close(); }
    @AfterAll static void close() { if(api!=null) api.close();if(database!=null) database.close(); }
    private ConfigurableApplicationContext launch(String... extra) {
        var args=new ArrayList<>(List.of(AuthTestSettings.arguments(database.apiConnectionUrl())));args.addAll(List.of(extra));args.add("--WORKER_IDLE_MS=60000");var application=UniCaronaApplication.application();application.addPrimarySources(List.of(ProviderConfiguration.class));return application.run(args.toArray(String[]::new));
    }
    private UUID email() {
        UUID id=UUID.randomUUID();String ciphertext=crypto.encrypt("https://example.test/synthetic-worker-only",AuthTestSettings.KEY);
        sql.update("INSERT INTO outbox_jobs(id,type,event_key,payload,status) VALUES(?,'email',?,jsonb_build_object('to','synthetic@example.test','kind','verify_email','urlCiphertext',?::text),'pending')",id,"synthetic-worker-"+id,ciphertext);return id;
    }
    private String state(UUID id) { return sql.queryForObject("SELECT status FROM outbox_jobs WHERE id=?",String.class,id); }
    @Test void defaultApiStartsHttpAndNeverAutomaticallyClaimsJobs() {
        assertInstanceOf(ServletWebServerApplicationContext.class,api);assertTrue(api.getBeansOfType(WorkerLifecycle.class).isEmpty());UUID id=email();assertEquals("pending",state(id));assertEquals(0,sql.queryForObject("SELECT attempts FROM outbox_jobs WHERE id=?",Integer.class,id));
    }
    @Test void workerModeFromImportedConfigurationHasNoHttpServerAndDeliversRealOutboxJob() throws Exception {
        UUID id=email();Path config=configuration.resolve("worker.properties");Files.writeString(config,"UNICARONA_MODE=worker\n");worker=launch("--spring.config.import="+config.toUri());assertFalse(worker instanceof ServletWebServerApplicationContext);assertFalse(worker.containsBean("handlerExceptionResolver"));assertEquals(1,worker.getBeansOfType(WorkerLifecycle.class).size());assertTrue(TrackingEmail.entered.await(5,TimeUnit.SECONDS));var done=new CountDownLatch(1);worker.getBean(WorkerLifecycle.class).stop(done::countDown);assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals("done",state(id));assertEquals(1,worker.getBean(TrackingEmail.class).calls.get());assertEquals(1,sql.queryForObject("SELECT attempts FROM outbox_jobs WHERE id=?",Integer.class,id));
    }
    @Test void contextShutdownKeepsDatasourceAliveUntilDeliveryCommitsAndDoesNotClaimNextJob() throws Exception {
        UUID active=email();TrackingEmail.release=new CountDownLatch(1);worker=launch("--UNICARONA_MODE=worker");assertTrue(TrackingEmail.entered.await(5,TimeUnit.SECONDS));assertEquals("processing",state(active));UUID later=email();var lifecycle=worker.getBean(WorkerLifecycle.class);var datasource=worker.getBean(HikariDataSource.class);var provider=worker.getBean(TrackingEmail.class);var done=new CountDownLatch(1);lifecycle.stop(done::countDown);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var closing=executor.submit(worker::close);
            try { assertEquals(1,done.getCount());assertTrue(lifecycle.isRunning());assertFalse(datasource.isClosed());assertEquals("pending",state(later)); }finally { TrackingEmail.release.countDown(); }
            closing.get(10,TimeUnit.SECONDS);assertTrue(done.await(5,TimeUnit.SECONDS));
        }
        assertTrue(datasource.isClosed());assertEquals("done",state(active));assertEquals("pending",state(later));assertEquals(0,sql.queryForObject("SELECT attempts FROM outbox_jobs WHERE id=?",Integer.class,later));assertEquals(1,provider.calls.get());
    }
}

