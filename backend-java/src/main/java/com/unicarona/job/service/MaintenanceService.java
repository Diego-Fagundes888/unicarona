package com.unicarona.job.service;

import com.unicarona.job.repository.MaintenanceRepository;
import com.unicarona.shared.TransactionRetries;
import java.util.function.BooleanSupplier;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Independent item transactions retain earlier commits if a later item fails. */
@Service
public class MaintenanceService {
    public record Counts(int requests,int finals,int subscriptions) {}
    private final MaintenanceRepository repository;private final MaintenanceTransactions transactions;private final TransactionRetries retries;
    private final List<MaintenanceTask> tasks;
    public MaintenanceService(MaintenanceRepository repository,MaintenanceTransactions transactions,TransactionRetries retries) { this(repository,transactions,retries,List.of()); }
    @Autowired public MaintenanceService(MaintenanceRepository repository,MaintenanceTransactions transactions,TransactionRetries retries,List<MaintenanceTask> tasks) { this.repository=repository;this.transactions=transactions;this.retries=retries;this.tasks=List.copyOf(tasks); }
    @Transactional(propagation=Propagation.NEVER) public Counts run() { return run(100,() -> false); }
    @Transactional(propagation=Propagation.NEVER) public Counts run(int batch) { return run(batch,() -> false); }
    @Transactional(propagation=Propagation.NEVER) public Counts run(int batch,BooleanSupplier stopping) {
        if(batch<1 || batch>10000) throw new IllegalArgumentException("Maintenance batch must be between 1 and 10000.");int requests=0,finals=0,subscriptions=0;
        if(stopping.getAsBoolean())return new Counts(0,0,0);
        for(var ref:repository.requests(batch)) { if(stopping.getAsBoolean()) return new Counts(requests,finals,subscriptions);if(retries.call(() -> transactions.request(ref))) requests++; }
        for(var ref:repository.finals(batch)) { if(stopping.getAsBoolean()) return new Counts(requests,finals,subscriptions);if(retries.call(() -> transactions.finalConfirmation(ref))) finals++; }
        for(var id:repository.contracts(batch)) { if(stopping.getAsBoolean()) return new Counts(requests,finals,subscriptions);if(retries.call(() -> transactions.subscription(id))) subscriptions++; }
        if(!stopping.getAsBoolean()) repository.pruneRateLimits();
        for(var task:tasks) {if(stopping.getAsBoolean())break;task.run(batch,stopping);}
        return new Counts(requests,finals,subscriptions);
    }
}
