package com.unicarona.job.service;

import com.unicarona.job.repository.MaintenanceRepository;
import com.unicarona.shared.TransactionRetries;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceServiceTest {
    private final MaintenanceRepository repository=mock(MaintenanceRepository.class);
    private final MaintenanceTransactions transactions=mock(MaintenanceTransactions.class);
    private final MaintenanceService maintenance=new MaintenanceService(repository,transactions,new TransactionRetries());
    @Test void countsOnlyCommittedChangesAndKeepsEachBatchBounded() {
        var a=new MaintenanceRepository.Request(UUID.randomUUID(),UUID.randomUUID());var b=new MaintenanceRepository.Request(UUID.randomUUID(),UUID.randomUUID());var f=new MaintenanceRepository.Final(UUID.randomUUID(),UUID.randomUUID());var c=UUID.randomUUID();when(repository.requests(2)).thenReturn(List.of(a,b));when(repository.finals(2)).thenReturn(List.of(f));when(repository.contracts(2)).thenReturn(List.of(c));when(transactions.request(a)).thenReturn(true);when(transactions.request(b)).thenReturn(false);when(transactions.finalConfirmation(f)).thenReturn(true);when(transactions.subscription(c)).thenReturn(true);
        assertEquals(new MaintenanceService.Counts(1,1,1),maintenance.run(2));verify(repository).pruneRateLimits();
    }
    @Test void shutdownStopsBetweenItemsAndDoesNotPruneOrStartLaterPhases() {
        var stopping=new AtomicBoolean();var a=new MaintenanceRepository.Request(UUID.randomUUID(),UUID.randomUUID());var b=new MaintenanceRepository.Request(UUID.randomUUID(),UUID.randomUUID());when(repository.requests(100)).thenReturn(List.of(a,b));when(transactions.request(a)).thenAnswer(call -> { stopping.set(true);return true; });assertEquals(new MaintenanceService.Counts(1,0,0),maintenance.run(100,stopping::get));verify(transactions,never()).request(b);verify(repository,never()).finals(anyInt());verify(repository,never()).pruneRateLimits();
    }
    @Test void serializationRetryDoesNotCountRolledBackAttempt() {
        var a=new MaintenanceRepository.Request(UUID.randomUUID(),UUID.randomUUID());when(repository.requests(100)).thenReturn(List.of(a));when(transactions.request(a)).thenThrow(new ConcurrencyFailureException("Synthetic",new SQLException("Synthetic","40001"))).thenReturn(true);assertEquals(1,maintenance.run().requests());verify(transactions,times(2)).request(a);
    }
    @Test void unsupportedBatchFailsBeforeAnyDatabaseWork() { assertThrows(IllegalArgumentException.class,() -> maintenance.run(0));assertThrows(IllegalArgumentException.class,() -> maintenance.run(10001));verifyNoInteractions(repository,transactions); }
    @Test void optionalProviderWorkStopsBeforeTheNextTaskWhenShutdownIsRequested() {
        var stopping=new AtomicBoolean();MaintenanceTask first=(batch,stop)->{assertEquals(3,batch);assertFalse(stop.getAsBoolean());stopping.set(true);};
        var second=mock(MaintenanceTask.class);
        var withProviders=new MaintenanceService(repository,transactions,new TransactionRetries(),List.of(first,second));
        withProviders.run(3,stopping::get);verifyNoInteractions(second);
    }
    @Test void aWithdrawnPermissionBeforeThePassStartsPreventsAllDatabaseWork(){assertEquals(new MaintenanceService.Counts(0,0,0),maintenance.run(100,()->true));verifyNoInteractions(repository,transactions);}
}
