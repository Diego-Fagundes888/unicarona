package com.unicarona.privacy.service;

import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.job.service.MaintenanceTask;
import com.unicarona.shared.TransactionRetries;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;

/** Each irreversible step is retryable. No payment history is deleted by this profile-erasure worker. */
@Service @org.springframework.core.annotation.Order(100)
public class PrivacyErasureMaintenance implements MaintenanceTask {
    private final PrivacyErasureTransactions transactions;private final PrivacyErasureJournal journal;private final PrivateFileStorage storage;private final TransactionRetries retries;private final PrivacyFileErasure files;private final PrivacySuppressionCompletions completions;
    public PrivacyErasureMaintenance(PrivacyErasureTransactions transactions,PrivacyErasureJournal journal,PrivateFileStorage storage,TransactionRetries retries,PrivacyFileErasure files,PrivacySuppressionCompletions completions){this.transactions=transactions;this.journal=journal;this.storage=storage;this.retries=retries;this.files=files;this.completions=completions;}
    public void replay(PrivacyErasureJournal.Entry entry){
        boolean needed=transactions.needsProfileReplay(entry.owner());
        if(!needed)for(String key:transactions.erasedFileKeys(entry.owner()))try{if(storage.existsVerified(key)){needed=true;break;}}catch(java.io.IOException error){throw new IllegalStateException("Cannot verify restored private-file suppression.");}
        if(!needed){completions.account(entry);return;}
        try{journal.reopenAccount(entry);}catch(java.io.IOException unavailable){throw new IllegalStateException("Cannot invalidate the restored account suppression proof.");}
        var task=retries.call(()->transactions.restore(entry));if(task==null)return;
        retries.run(()->transactions.scrub(task));
        for(var file:transactions.restoreFiles(task.owner()))erase(file);
        retries.call(()->transactions.complete(task));completions.account(entry);
    }
    @Override public void run(int batch,BooleanSupplier stopping){
        for(var closure:transactions.due(batch)){if(stopping.getAsBoolean())return;retries.call(()->transactions.prepare(closure));}
        var tasks=transactions.tasks(batch);
        for(var task:tasks){if(stopping.getAsBoolean())return;try{journal.ensure(task.owner(),task.closure(),task.prepared(),task.suppressionUntil());retries.run(()->transactions.scrub(task));}catch(Exception error){transactions.failed(task.closure());LoggerFactory.getLogger(getClass()).warn("Privacy erasure pending; code=ERASURE_RETRY");}}
        for(var file:transactions.files(batch)){if(stopping.getAsBoolean())return;erase(file);}
        for(var task:tasks){if(stopping.getAsBoolean())return;if(retries.call(()->transactions.complete(task)))completions.account(task.closure());}
        for(var file:transactions.recordedErasedFilesDue(batch)){if(stopping.getAsBoolean())return;
            try{files.suppressRecorded(file.id());}catch(Exception error){LoggerFactory.getLogger(getClass()).warn("event=restored_file_suppression_pending code=FILE_ERASURE_RETRY");}
            finally{retries.run(()->transactions.recordedFileChecked(file));}
        }
    }
    public void suppressRecordedFilesBeforeReadiness()throws java.io.IOException {
        java.util.UUID after=null;while(true){var files=transactions.recordedErasedFiles(after,1000);if(files.isEmpty())return;
            for(var file:files){this.files.suppressRecorded(file.id());retries.run(()->transactions.recordedFileChecked(file));after=file.id();}
        }
    }
    private void erase(PrivacyErasureTransactions.File file){try{files.erase(file.id());}catch(Exception error){transactions.fileFailed(file);LoggerFactory.getLogger(getClass()).warn("Private erasure pending; code=FILE_ERASURE_RETRY");}}
}
