package com.unicarona.privacy.service;

import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/** Bounded flat inventory, fair cursor and one-minute cadence; a failed scan never becomes approval. */
@Service @Order(95)
public class PrivacyOrphanFiles implements MaintenanceTask {
    public record Result(int inspected,int erased,int observed,int pruned){}
    private final PrivateFileStorage storage;private final PrivacyOrphanFileTransactions transactions;private final PrivacyBackupCatalog catalog;private final PrivacyReadiness readiness;private UUID after,confirmationAfter;private long next;
    public PrivacyOrphanFiles(PrivateFileStorage storage,PrivacyOrphanFileTransactions transactions,PrivacyBackupCatalog catalog,PrivacyReadiness readiness){this.storage=storage;this.transactions=transactions;this.catalog=catalog;this.readiness=readiness;}
    public synchronized Result runOnce(int batch,BooleanSupplier stopping)throws IOException {
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid orphan-file batch.");if(!catalog.enabled()||!readiness.ready()||stopping.getAsBoolean())return new Result(0,0,0,0);
        int limit=Math.min(batch,25),inspected=0,erased=0,observed=0,pruned=transactions.pruneObservations(limit);var pending=transactions.pendingCompletions(confirmationAfter,limit);if(pending.isEmpty())confirmationAfter=null;for(var entry:pending){if(stopping.getAsBoolean())return new Result(0,0,0,pruned);transactions.confirmAbsent(entry);confirmationAfter=entry.file();}
        var files=storage.inventory(after,limit);if(files.isEmpty()){after=null;return new Result(0,0,0,pruned);}
        for(UUID file:files){if(stopping.getAsBoolean())break;var result=transactions.inspect(file);after=file;inspected++;if(result==PrivacyOrphanFileTransactions.Outcome.ERASED)erased++;if(result==PrivacyOrphanFileTransactions.Outcome.OBSERVED)observed++;}
        return new Result(inspected,erased,observed,pruned);
    }
    @Override public synchronized void run(int batch,BooleanSupplier stopping){
        if(stopping.getAsBoolean()||!catalog.enabled()||!readiness.ready()||(next!=0&&System.nanoTime()-next<0))return;next=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);
        try{runOnce(batch,stopping);}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=orphan_file_cleanup_pending code=PRIVATE_ORPHAN_RETRY");}
    }
}
