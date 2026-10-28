package com.unicarona.privacy.service;

import com.unicarona.file.storage.*;
import com.unicarona.privacy.config.PrivacySettings;
import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Classifies bytes with no metadata without fabricating a titular, account or upload receipt. */
@Service
public class PrivacyOrphanFileTransactions {
    public enum Outcome { KNOWN,HELD,BUSY,MISSING,OBSERVED,WAITING,ERASED }
    private final JdbcTemplate sql;private final PrivateFileStorage storage;private final PrivacyErasureJournal journal;private final PrivacySettings settings;private final PrivacyHoldControlTransactions controls;private final PrivacyReadiness readiness;private final AuditRepository audit;
    public PrivacyOrphanFileTransactions(JdbcTemplate sql,PrivateFileStorage storage,PrivacyErasureJournal journal,PrivacySettings settings,PrivacyHoldControlTransactions controls,PrivacyReadiness readiness,AuditRepository audit){this.sql=sql;this.storage=storage;this.journal=journal;this.settings=settings;this.controls=controls;this.readiness=readiness;this.audit=audit;}
    private void current()throws IOException {if(!readiness.ready())throw new IOException("Privacy recovery is not ready for orphan classification.");controls.requireCurrentForMaintenance();}
    private boolean known(UUID file){return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM stored_files WHERE id=? OR storage_key=?)",Boolean.class,file,file.toString()));}
    private boolean held(UUID file,UUID owner){return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_legal_holds WHERE category='files' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?) AND (?::uuid IS NULL OR user_id=?::uuid))",Boolean.class,file,owner,owner));}
    private void forget(UUID file){sql.update("DELETE FROM privacy_orphan_file_observations WHERE file_id=?",file);}
    @Transactional(rollbackFor=IOException.class) public Outcome inspect(UUID file)throws IOException {
        current();if(!PrivateFileLock.tryAcquire(sql,file))return Outcome.BUSY;
        if(known(file)){forget(file);return Outcome.KNOWN;}if(held(file,null)){forget(file);return Outcome.HELD;}
        var observed=storage.observe(file);if(observed.isEmpty()){forget(file);return Outcome.MISSING;}var value=observed.get();
        var previous=sql.queryForList("SELECT byte_size,content_sha256,fingerprint,first_seen_at<=now()-interval '24 hours' AS mature FROM privacy_orphan_file_observations WHERE file_id=? FOR UPDATE",file);
        if(previous.isEmpty()||!value.fingerprint().equals(previous.getFirst().get("fingerprint"))||value.bytes()!=((Number)previous.getFirst().get("byte_size")).intValue()||!value.sha256().equals(previous.getFirst().get("content_sha256"))){
            sql.update("INSERT INTO privacy_orphan_file_observations(file_id,byte_size,content_sha256,fingerprint) VALUES(?,?,?,?) ON CONFLICT(file_id) DO UPDATE SET byte_size=excluded.byte_size,content_sha256=excluded.content_sha256,fingerprint=excluded.fingerprint,first_seen_at=now(),last_seen_at=now()",file,value.bytes(),value.sha256(),value.fingerprint());return Outcome.OBSERVED;
        }
        sql.update("UPDATE privacy_orphan_file_observations SET last_seen_at=now() WHERE file_id=?",file);if(!Boolean.TRUE.equals(previous.getFirst().get("mature")))return Outcome.WAITING;
        Instant prepared=sql.queryForObject("SELECT now()",Timestamp.class).toInstant();var entry=journal.ensureOrphan(file,value.bytes(),value.sha256(),prepared,prepared.plus(Duration.ofDays(settings.backupRetentionDays()+2)));
        storage.eraseExact(file,value.bytes(),value.sha256());forget(file);audit.record(null,"orphan_file_erased","stored_file",file,Map.of("classification","unregistered_bytes","ownerInferred",false));confirmAfterCommit(entry);return Outcome.ERASED;
    }
    private void confirmAfterCommit(PrivacyErasureJournal.OrphanEntry entry){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){try{journal.confirmOrphanAbsent(entry);}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(PrivacyOrphanFileTransactions.class).warn("event=orphan_confirmation_pending code=ORPHAN_ABSENCE_RETRY");}}});}
    public List<PrivacyErasureJournal.OrphanEntry> pendingCompletions(UUID after,int batch)throws IOException {return journal.orphanEntries().stream().filter(e->e.confirmedAbsentAt()==null&&(after==null||e.file().toString().compareTo(after.toString())>0)).sorted(Comparator.comparing(e->e.file().toString())).limit(batch).toList();}
    @Transactional(rollbackFor=IOException.class) public boolean confirmAbsent(PrivacyErasureJournal.OrphanEntry entry)throws IOException {
        current();if(!PrivateFileLock.tryAcquire(sql,entry.file())||storage.observe(entry.file()).isPresent())return false;
        var rows=sql.queryForList("SELECT id,owner_id,storage_key,byte_size,sha256,erased_at,deleted_at FROM stored_files WHERE id=? OR storage_key=?",entry.file(),entry.file().toString());
        for(var row:rows)if(!entry.file().equals(row.get("id"))||!entry.file().toString().equals(row.get("storage_key"))||entry.bytes()!=((Number)row.get("byte_size")).intValue()||row.get("erased_at")==null||row.get("deleted_at")==null||!(entry.sha256().equals(row.get("sha256"))||"erased".equals(row.get("sha256"))))return false;
        if(held(entry.file(),rows.isEmpty()?null:(UUID)rows.getFirst().get("owner_id")))return false;forget(entry.file());confirmAfterCommit(entry);return true;
    }
    /** Exact signed content is suppressed even when an older DB reintroduces a live file link.
     * Conflicting bytes or identity require assistance instead of overwriting/deleting blindly. */
    @Transactional(rollbackFor=IOException.class) public boolean replay(PrivacyErasureJournal.OrphanEntry entry)throws IOException {
        controls.requireCurrentForMaintenance();PrivateFileLock.acquire(sql,entry.file());
        var rows=sql.queryForList("SELECT id,owner_id,storage_key,byte_size,sha256 FROM stored_files WHERE id=? OR storage_key=? FOR UPDATE",entry.file(),entry.file().toString());
        for(var row:rows)if(!entry.file().equals(row.get("id"))||!entry.file().toString().equals(row.get("storage_key"))||entry.bytes()!=((Number)row.get("byte_size")).intValue()||!(entry.sha256().equals(row.get("sha256"))||"erased".equals(row.get("sha256"))))throw new IOException("Restored orphan file metadata conflicts with its signed suppression.");
        var physical=storage.observe(entry.file());if(physical.isPresent()&&(physical.get().bytes()!=entry.bytes()||!physical.get().sha256().equals(entry.sha256())))throw new IOException("Restored orphan file bytes conflict with their signed suppression.");
        if(physical.isPresent())journal.reopenOrphan(entry);
        UUID owner=rows.isEmpty()?null:(UUID)rows.getFirst().get("owner_id");boolean held=held(entry.file(),owner);
        if(held&&!rows.isEmpty()&&physical.isEmpty())throw new IOException("Preserved private file bytes are unavailable during recovery.");
        if(!rows.isEmpty()){
            sql.update("UPDATE users SET photo_file_id=NULL,updated_at=now() WHERE photo_file_id=?",entry.file());
            sql.update("UPDATE stored_files SET deleted_at=coalesce(deleted_at,now()),erasure_requested_at=coalesce(erasure_requested_at,now()),erased_at=NULL WHERE id=?",entry.file());
        }
        forget(entry.file());if(held)return false;storage.eraseExact(entry.file(),entry.bytes(),entry.sha256());
        if(!rows.isEmpty())sql.update("UPDATE stored_files SET erased_at=now(),sha256='erased',erasure_last_scan_at=now() WHERE id=?",entry.file());confirmAfterCommit(entry);return true;
    }
    @Transactional(rollbackFor=IOException.class) public int pruneObservations(int batch)throws IOException {
        current();var rows=sql.query("SELECT o.file_id FROM privacy_orphan_file_observations o WHERE o.last_seen_at<=now()-interval '7 days' OR EXISTS(SELECT 1 FROM stored_files f WHERE f.id=o.file_id OR f.storage_key=o.file_id::text) ORDER BY o.last_seen_at,o.file_id LIMIT ?",(r,n)->r.getObject(1,UUID.class),batch);int removed=0;
        for(UUID id:rows)if(PrivateFileLock.tryAcquire(sql,id)&&(known(id)||storage.observe(id).isEmpty())){forget(id);removed++;}return removed;
    }
}
