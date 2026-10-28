package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/** Failure preserves suppression evidence and retries, without starving payment/mail work. */
@Service @Order(300)
public class PrivacyCopiesMaintenance implements MaintenanceTask {
    public record Result(int erasedPaths,int completedCopies,int expiredJournalEntries,int rekeyedRecords){}
    private final PrivacyBackupCatalog catalog;private final PrivacyErasureJournal journal;private final PrivacyRestoreFence fence;private final PrivacySuppressionCompletions completions;
    private long nextAttempt;
    public PrivacyCopiesMaintenance(PrivacyBackupCatalog catalog,PrivacyErasureJournal journal,PrivacyRestoreFence fence,PrivacySuppressionCompletions completions){this.catalog=catalog;this.journal=journal;this.fence=fence;this.completions=completions;}
    public Result runOnce(int batch)throws IOException {
        if(!catalog.enabled())return new Result(0,0,0,0);
        journal.entries();fence.checkBeforeReplay();completions.confirmBatch(batch);
        var copies=catalog.locked(()->catalog.prune(batch));
        int resigned=catalog.locked(()->catalog.rekey(batch));resigned+=journal.rekey(batch);
        // The active database is current before committing a stronger independent restore floor.
        fence.markObservedAtLeast(completions.latestVerifiedHorizon());int retired=completions.retire(batch);
        return new Result(copies.deletedPaths(),copies.completedCopies(),retired,resigned);
    }
    @Override public synchronized void run(int batch,BooleanSupplier stopping){
        if(!catalog.enabled()||stopping.getAsBoolean()||(nextAttempt!=0&&System.nanoTime()-nextAttempt<0))return;
        nextAttempt=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);
        try{runOnce(batch);}catch(Exception retry){LoggerFactory.getLogger(getClass()).warn("event=privacy_copies_pending code=PRIVATE_COPY_RETENTION_RETRY");}
    }
}
