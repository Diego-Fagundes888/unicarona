package com.unicarona.privacy.service;

import com.unicarona.shared.ApiException;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Connects business decisions to the independent control; never changes approval outcome by guessing. */
@Service
public class PrivacyHoldRecovery {
    private final PrivacyHoldControlTransactions controls;private final PrivacyRetentionSnapshot snapshots;private final PrivacyHoldControlJournal journal;private final PrivacyRetainedFileBlobs blobs;private final JdbcTemplate sql;private final PrivacyReadiness readiness;private final TransactionTemplate transaction;
    public PrivacyHoldRecovery(PrivacyHoldControlTransactions controls,PrivacyRetentionSnapshot snapshots,PrivacyHoldControlJournal journal,PrivacyRetainedFileBlobs blobs,JdbcTemplate sql,PrivacyReadiness readiness,PlatformTransactionManager manager){this.controls=controls;this.snapshots=snapshots;this.journal=journal;this.blobs=blobs;this.sql=sql;this.readiness=readiness;transaction=new TransactionTemplate(manager);transaction.setTimeout(30);}
    public void stage(){controls.stage(()->{try{return snapshots.capture();}catch(IOException invalid){readiness.begin();throw unavailable();}});}
    public void beforeReadiness(){
        controls.recoverCommitted(snapshots::replay);
        if(journal.enabled())transaction.executeWithoutResult(ignored->{
            PrivacyRetentionLock.acquire(sql);
            // Adopt pre-feature evidence only when no control has ever been established; subsequent
            // startups refresh a confirmed control after replay, including expiry and key changes.
            if(controls.marker().sequence()>0||Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_legal_holds UNION ALL SELECT 1 FROM privacy_retained_records)",Boolean.class)))stage();
        });
    }
    /** Independent data cannot outlive its purpose because an old superseded head was forgotten. */
    public int cleanup(int batch)throws IOException {
        if(!journal.enabled())return 0;
        try{return transaction.execute(ignored->{PrivacyRetentionLock.acquire(sql);try{var current=journal.current();if(current.isPresent())snapshots.verifyForCleanup(current.get().state());return journal.pruneObsolete(batch)+blobs.pruneUnreferenced(batch);}catch(IOException invalid){throw new CleanupFailure();}});}
        catch(CleanupFailure invalid){throw new IOException("Preservation recovery cleanup needs a verified control.");}
    }
    private static class CleanupFailure extends RuntimeException {}
    private static ApiException unavailable(){return new ApiException("PRIVACY_CONTROL_UNAVAILABLE","Não foi possível preparar o controle de preservação. Nenhuma decisão pendente foi confirmada.",503);}
}
