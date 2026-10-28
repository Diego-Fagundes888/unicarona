package com.unicarona.privacy.service;

import com.unicarona.file.storage.*;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import static com.unicarona.privacy.service.PrivacyErasureJournal.Verification.*;

/** Database-backed completion proofs. The journal's signature proves the instruction; it does
 * not by itself prove that the current profile, metadata or physical files were suppressed. */
@Service
public class PrivacySuppressionConfirmationTransactions {
    private final JdbcTemplate sql;private final PrivateFileStorage storage;private final PrivacyErasureJournal journal;private final PrivacyHoldControlTransactions controls;
    public PrivacySuppressionConfirmationTransactions(JdbcTemplate sql,PrivateFileStorage storage,PrivacyErasureJournal journal,PrivacyHoldControlTransactions controls){this.sql=sql;this.storage=storage;this.journal=journal;this.controls=controls;}
    private boolean exists(String query,Object... args){return Boolean.TRUE.equals(sql.queryForObject(query,Boolean.class,args));}
    private boolean held(UUID owner,UUID file){return exists("SELECT EXISTS(SELECT 1 FROM privacy_legal_holds WHERE category='files' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?) AND (?::uuid IS NULL OR user_id=?::uuid))",file,owner,owner);}
    private PrivacyErasureJournal.Verification accountState(PrivacyErasureJournal.Entry entry)throws IOException {
        UUID owner=entry.owner();var rows=sql.queryForList("SELECT * FROM users WHERE id=? FOR UPDATE SKIP LOCKED",owner);if(rows.isEmpty())return exists("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",owner)?BUSY:COMPLETE;
        if(PrivacyProfileSuppression.needsScrub(sql,owner)||exists("SELECT EXISTS(SELECT 1 FROM privacy_erasure_tasks WHERE user_id=? AND status<>'completed')",owner))return INCOMPLETE;
        var files=sql.queryForList("SELECT * FROM stored_files WHERE owner_id=? ORDER BY id",owner);for(var file:files){UUID id=(UUID)file.get("id");if(!PrivateFileLock.tryAcquire(sql,id))return BUSY;if(!id.toString().equals(file.get("storage_key")))throw new IOException("Account file identity conflicts with its suppression instruction.");
            if(file.get("deleted_at")==null||file.get("erasure_requested_at")==null)return INCOMPLETE;
            if(held(owner,id)){var physical=storage.observe(id);if(physical.isEmpty()||physical.get().bytes()!=((Number)file.get("byte_size")).intValue()||!physical.get().sha256().equals(file.get("sha256")))return INCOMPLETE;}
            else if(file.get("erased_at")==null||!"erased".equals(file.get("sha256"))||storage.existsVerified(id.toString()))return INCOMPLETE;
        }return COMPLETE;
    }
    private PrivacyErasureJournal.Verification fileState(PrivacyErasureJournal.FileEntry entry)throws IOException {
        if(!PrivateFileLock.tryAcquire(sql,entry.file()))return BUSY;var rows=sql.queryForList("SELECT * FROM stored_files WHERE id=? OR storage_key=? FOR UPDATE SKIP LOCKED",entry.file(),entry.file().toString());
        if(rows.isEmpty()&&exists("SELECT EXISTS(SELECT 1 FROM stored_files WHERE id=? OR storage_key=?)",entry.file(),entry.file().toString()))return BUSY;
        for(var row:rows){if(!entry.file().equals(row.get("id"))||!entry.owner().equals(row.get("owner_id"))||!entry.file().toString().equals(row.get("storage_key")))throw new IOException("Known file identity conflicts with its suppression instruction.");if(row.get("erased_at")==null||row.get("deleted_at")==null||row.get("erasure_requested_at")==null||!"erased".equals(row.get("sha256")))return INCOMPLETE;}
        return held(entry.owner(),entry.file())||storage.existsVerified(entry.file().toString())?INCOMPLETE:COMPLETE;
    }
    private PrivacyErasureJournal.Verification orphanState(PrivacyErasureJournal.OrphanEntry entry)throws IOException {
        if(!PrivateFileLock.tryAcquire(sql,entry.file()))return BUSY;var rows=sql.queryForList("SELECT * FROM stored_files WHERE id=? OR storage_key=? FOR UPDATE SKIP LOCKED",entry.file(),entry.file().toString());if(rows.isEmpty()&&exists("SELECT EXISTS(SELECT 1 FROM stored_files WHERE id=? OR storage_key=?)",entry.file(),entry.file().toString()))return BUSY;
        for(var row:rows){if(!entry.file().equals(row.get("id"))||!entry.file().toString().equals(row.get("storage_key"))||entry.bytes()!=((Number)row.get("byte_size")).intValue()||!(entry.sha256().equals(row.get("sha256"))||"erased".equals(row.get("sha256"))))throw new IOException("Orphan metadata conflicts with its suppression instruction.");if(row.get("erased_at")==null||row.get("deleted_at")==null||row.get("erasure_requested_at")==null)return INCOMPLETE;}
        var bytes=storage.observe(entry.file());if(bytes.isPresent()){if(bytes.get().bytes()!=entry.bytes()||!bytes.get().sha256().equals(entry.sha256()))throw new IOException("Orphan bytes conflict with their suppression instruction.");return INCOMPLETE;}return held(rows.isEmpty()?null:(UUID)rows.getFirst().get("owner_id"),entry.file())?INCOMPLETE:COMPLETE;
    }
    private void afterCommit(Runnable action){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){try{action.run();}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(PrivacySuppressionConfirmationTransactions.class).warn("event=suppression_confirmation_pending code=SUPPRESSION_PROOF_RETRY");}}});}
    @FunctionalInterface private interface Completion {void run()throws IOException;}
    private Runnable completion(Completion action){return ()->{try{action.run();}catch(IOException unavailable){throw new java.io.UncheckedIOException(unavailable);}};}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=IOException.class)
    public PrivacyErasureJournal.Verification account(PrivacyErasureJournal.Entry entry)throws IOException {
        controls.requireCurrentForMaintenance();var state=accountState(entry);if(state==COMPLETE)afterCommit(completion(()->journal.confirmAccount(entry)));else if(state==INCOMPLETE)journal.reopenAccount(entry);return state;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=IOException.class)
    public PrivacyErasureJournal.Verification file(PrivacyErasureJournal.FileEntry entry)throws IOException {
        controls.requireCurrentForMaintenance();var state=fileState(entry);if(state==COMPLETE)afterCommit(completion(()->journal.confirmFileAbsent(entry)));else if(state==INCOMPLETE)journal.reopenFile(entry);return state;
    }
    private PrivacyErasureJournal.Verification horizon(PrivacyErasureJournal.Verification state,java.time.Instant confirmation){if(state!=COMPLETE)return state;return confirmation!=null&&exists("SELECT observed_at>=?::timestamptz FROM privacy_restore_state WHERE singleton",Timestamp.from(PrivacyRestoreFence.ceiling(confirmation)))?COMPLETE:BUSY;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=IOException.class)
    public int retire(int batch)throws IOException {
        controls.requireCurrentForMaintenance();return journal.pruneExpired(batch,new PrivacyErasureJournal.RetirementVerifier(){
            public PrivacyErasureJournal.Verification account(PrivacyErasureJournal.Entry e)throws IOException {return horizon(accountState(e),e.confirmedAt());}
            public PrivacyErasureJournal.Verification file(PrivacyErasureJournal.FileEntry e)throws IOException {return horizon(fileState(e),e.confirmedAt());}
            public PrivacyErasureJournal.Verification orphan(PrivacyErasureJournal.OrphanEntry e)throws IOException {return horizon(orphanState(e),e.confirmedAbsentAt());}
        });
    }
}
