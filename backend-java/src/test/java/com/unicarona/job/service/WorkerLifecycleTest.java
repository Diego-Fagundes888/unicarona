package com.unicarona.job.service;

import com.unicarona.job.config.WorkerSettings;
import com.unicarona.shared.ApiException;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerLifecycleTest {
    private final MaintenanceService maintenance=mock(MaintenanceService.class);
    private final OutboxProcessor outbox=mock(OutboxProcessor.class);
    private WorkerLifecycle worker;
    private final com.unicarona.privacy.service.PrivacyReadiness privacy=new com.unicarona.privacy.service.PrivacyReadiness();
    @BeforeEach void ready(){privacy.complete();}
    private WorkerSettings settings(long maintenanceMillis,long idleMillis) { return new WorkerSettings(new MockEnvironment().withProperty("WORKER_MAINTENANCE_INTERVAL_MS",""+maintenanceMillis).withProperty("WORKER_IDLE_MS",""+idleMillis)); }
    @AfterEach void stop() { if(worker!=null) worker.stop(); }
    @Test void shutdownDrainsDeliveryAndWakesIdleWithoutStartingAnotherJob() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        when(outbox.processOne()).thenAnswer(call -> { entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return true; });
        worker=new WorkerLifecycle(maintenance,outbox,settings(15000,60000),privacy);worker.start();worker.start();
        try { assertTrue(entered.await(5,TimeUnit.SECONDS));worker.stop(done::countDown);assertTrue(worker.isRunning());assertEquals(1,done.getCount()); }
        finally { release.countDown(); }
        assertTrue(done.await(5,TimeUnit.SECONDS));assertFalse(worker.isRunning());verify(outbox,times(1)).processOne();verify(maintenance,times(1)).run(eq(100),any());
    }
    @Test void maintenanceReceivesShutdownSignalAtItemBoundariesAndOutboxDoesNotStart() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        when(maintenance.run(eq(100),any())).thenAnswer(call -> { var stopping=call.getArgument(1,java.util.function.BooleanSupplier.class);assertFalse(stopping.getAsBoolean());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));assertTrue(stopping.getAsBoolean());return new MaintenanceService.Counts(1,0,0); });
        worker=new WorkerLifecycle(maintenance,outbox,settings(15000,1000),privacy);worker.start();
        try { assertTrue(entered.await(5,TimeUnit.SECONDS));worker.stop(done::countDown);assertEquals(1,done.getCount()); }finally { release.countDown(); }
        assertTrue(done.await(5,TimeUnit.SECONDS));verifyNoInteractions(outbox);
    }
    @Test void emptyQueueWaitIsWokenImmediatelyByStop() throws Exception {
        var entered=new CountDownLatch(1);when(outbox.processOne()).thenAnswer(call -> { entered.countDown();return false; });worker=new WorkerLifecycle(maintenance,outbox,settings(15000,60000),privacy);worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
        var done=new CountDownLatch(1);worker.stop(done::countDown);assertTrue(done.await(2,TimeUnit.SECONDS));assertFalse(worker.isRunning());
    }
    @Test void recoverableMaintenanceFailureRetriesAndThenDelivers() throws Exception {
        var delivered=new CountDownLatch(1);var attempts=new AtomicInteger();when(maintenance.run(eq(100),any())).thenAnswer(call -> { if(attempts.incrementAndGet()==1) throw new IllegalStateException("private provider message");return new MaintenanceService.Counts(0,0,0); });when(outbox.processOne()).thenAnswer(call -> { delivered.countDown();return false; });
        worker=new WorkerLifecycle(maintenance,outbox,settings(15000,1),privacy);worker.start();assertTrue(delivered.await(5,TimeUnit.SECONDS));worker.stop();assertEquals(2,attempts.get());
    }
    @Test void periodicMaintenanceUsesItsOwnIntervalRatherThanRunningForEveryJob() throws Exception {
        var twice=new CountDownLatch(2);when(maintenance.run(eq(100),any())).thenAnswer(call -> { twice.countDown();return new MaintenanceService.Counts(0,0,0); });when(outbox.processOne()).thenReturn(false);
        worker=new WorkerLifecycle(maintenance,outbox,settings(20,1),privacy);worker.start();assertTrue(twice.await(5,TimeUnit.SECONDS));worker.stop();verify(outbox,atLeast(2)).processOne();
    }
    @Test void failureCodesNeverIncludeMessagesAndHandleCyclicCauseChains() {
        assertEquals("WORKER_ERROR",WorkerLifecycle.errorCode(new IllegalStateException("private-token")));assertEquals("40001",WorkerLifecycle.errorCode(new IllegalStateException("private",new SQLException("private","40001"))));assertEquals("WORKER_ERROR",WorkerLifecycle.errorCode(new SQLException("private","private-state")));assertEquals("CLASS_MISMATCH",WorkerLifecycle.errorCode(new ApiException("CLASS_MISMATCH","private",422)));assertEquals("WORKER_ERROR",WorkerLifecycle.errorCode(new ApiException("unsafe private code","private",500)));
        var first=new RuntimeException();var second=new RuntimeException(first);first.initCause(second);assertEquals("WORKER_ERROR",WorkerLifecycle.errorCode(first));
    }
    @Test void intervalConfigurationHasBoundedPositiveDefaults() {
        var defaults=new WorkerSettings(new MockEnvironment());assertEquals(15000,defaults.maintenanceMillis());assertEquals(1000,defaults.idleMillis());for(long value:new long[]{0,-1,86400001}) assertThrows(IllegalArgumentException.class,() -> settings(value,1));assertThrows(IllegalArgumentException.class,() -> settings(1,0));
    }
    @Test void stoppedLifecycleInvokesCallbackAndCanRestart() throws Exception {
        var delivered=new CountDownLatch(2);when(outbox.processOne()).thenAnswer(call -> { delivered.countDown();return false; });worker=new WorkerLifecycle(maintenance,outbox,settings(15000,1),privacy);var stopped=new AtomicInteger();worker.stop(stopped::incrementAndGet);assertEquals(1,stopped.get());worker.start();assertTrue(delivered.await(5,TimeUnit.SECONDS));worker.stop();worker.start();worker.stop();assertFalse(worker.isRunning());assertEquals(Integer.MAX_VALUE,worker.getPhase());
    }
    @Test void restoredDataCannotReachMaintenanceOrEmailUntilPrivacyRecoveryFinishes()throws Exception {
        privacy.begin();var delivered=new CountDownLatch(1);when(outbox.processOne()).thenAnswer(call->{delivered.countDown();return false;});worker=new WorkerLifecycle(maintenance,outbox,settings(15000,60000),privacy);worker.start();
        assertFalse(delivered.await(100,TimeUnit.MILLISECONDS));verifyNoInteractions(maintenance,outbox);privacy.complete();assertTrue(delivered.await(5,TimeUnit.SECONDS));
    }
    @Test void stoppingAWorkerWaitingForPrivacyRecoveryDoesNotWaitForRecoveryOrQueueIdle()throws Exception {
        privacy.begin();worker=new WorkerLifecycle(maintenance,outbox,settings(15000,60000),privacy);worker.start();var done=new CountDownLatch(1);worker.stop(done::countDown);assertTrue(done.await(2,TimeUnit.SECONDS));verifyNoInteractions(maintenance,outbox);
    }
    @Test void privacyWithdrawnDuringMaintenanceStopsAtItemBoundariesAndDoesNotStartAnEmail()throws Exception {
        var entered=new CountDownLatch(1);var delivered=new CountDownLatch(1);var first=new AtomicBoolean(true);
        when(maintenance.run(eq(100),any())).thenAnswer(call->{var stopping=call.getArgument(1,java.util.function.BooleanSupplier.class);assertFalse(stopping.getAsBoolean());if(first.getAndSet(false)){privacy.begin();assertTrue(stopping.getAsBoolean());entered.countDown();}return new MaintenanceService.Counts(0,0,0);});
        when(outbox.processOne()).thenAnswer(call->{delivered.countDown();return false;});worker=new WorkerLifecycle(maintenance,outbox,settings(15000,60000),privacy);worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(delivered.await(100,TimeUnit.MILLISECONDS));verifyNoInteractions(outbox);privacy.complete();assertTrue(delivered.await(5,TimeUnit.SECONDS));
    }
}
