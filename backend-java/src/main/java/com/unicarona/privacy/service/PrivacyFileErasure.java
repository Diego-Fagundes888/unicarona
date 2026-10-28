package com.unicarona.privacy.service;

import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.file.storage.PrivateFileLock;
import java.io.IOException;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import com.unicarona.privacy.config.PrivacySettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recheck a hold under the same lock used by its creation; do not erase from an earlier candidate list. */
@Service
public class PrivacyFileErasure {
    private final JdbcTemplate sql;private final PrivateFileStorage storage;private final PrivacyErasureJournal journal;private final PrivacySettings policy;private final Clock clock;private final PrivacySuppressionCompletions completions;
    public PrivacyFileErasure(JdbcTemplate sql,PrivateFileStorage storage,PrivacyErasureJournal journal,PrivacySettings policy,Clock clock,PrivacySuppressionCompletions completions){this.sql=sql;this.storage=storage;this.journal=journal;this.policy=policy;this.clock=clock;this.completions=completions;}
    @Transactional(rollbackFor=IOException.class) public boolean erase(UUID id)throws IOException {
        PrivacyRetentionLock.acquire(sql);PrivateFileLock.acquire(sql,id);
        var rows=sql.queryForList("SELECT id,storage_key,owner_id,erased_at,erasure_requested_at FROM stored_files WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())return false;var row=rows.getFirst();if(row.get("erased_at")!=null||row.get("erasure_requested_at")==null)return false;
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_legal_holds WHERE user_id=? AND category='files' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?))",Boolean.class,row.get("owner_id"),id)))return false;
        if(!id.toString().equals(row.get("storage_key")))throw new IOException("Private file identity does not match its suppression key.");
        var at=clock.instant();var entry=journal.ensureFile((UUID)row.get("owner_id"),id,at,at.plus(Duration.ofDays(policy.backupRetentionDays()+2)));
        storage.eraseVerified((String)row.get("storage_key"));
        sql.update("UPDATE stored_files SET erased_at=coalesce(erased_at,now()),sha256='erased' WHERE id=?",id);completions.fileAfterCommit(entry);return true;
    }
    /** An old database may re-link a deleted photo; signed deletion still wins over that link. */
    @Transactional(rollbackFor=IOException.class) public boolean replay(PrivacyErasureJournal.FileEntry entry)throws IOException {
        PrivacyRetentionLock.acquire(sql);sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",entry.owner());PrivateFileLock.acquire(sql,entry.file());
        var rows=sql.queryForList("SELECT owner_id,storage_key,deleted_at,erasure_requested_at,erased_at,sha256 FROM stored_files WHERE id=? FOR UPDATE",entry.file());boolean physical=storage.existsVerified(entry.file().toString());
        if(!rows.isEmpty()){
            var row=rows.getFirst();if(!entry.owner().equals(row.get("owner_id"))||!entry.file().toString().equals(row.get("storage_key")))throw new IOException("Restored file conflicts with its independent suppression identity.");
            sql.update("UPDATE users SET photo_file_id=NULL,updated_at=now() WHERE id=? AND photo_file_id=?",entry.owner(),entry.file());
            if(!physical&&row.get("deleted_at")!=null&&row.get("erasure_requested_at")!=null&&row.get("erased_at")!=null&&"erased".equals(row.get("sha256"))){completions.fileAfterCommit(entry);return true;}
            journal.reopenFile(entry);
            // Mark the ordinary endpoint unavailable even when an active case preserves bytes.
            sql.update("UPDATE stored_files SET deleted_at=coalesce(deleted_at,now()),erasure_requested_at=coalesce(erasure_requested_at,now()),erased_at=NULL WHERE id=?",entry.file());
        }
        if(rows.isEmpty()&&physical)journal.reopenFile(entry);
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_legal_holds WHERE user_id=? AND category='files' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?))",Boolean.class,entry.owner(),entry.file())))return false;
        storage.eraseVerified(entry.file().toString());
        if(!rows.isEmpty())sql.update("UPDATE stored_files SET erased_at=now(),sha256='erased',erasure_last_scan_at=now() WHERE id=?",entry.file());completions.fileAfterCommit(entry);return true;
    }
    /** Reintroduced bytes invalidate the previous completion even if the DB still records erasure. */
    @Transactional(rollbackFor=IOException.class) public boolean suppressRecorded(UUID id)throws IOException {
        PrivacyRetentionLock.acquire(sql);PrivateFileLock.acquire(sql,id);var rows=sql.queryForList("SELECT owner_id,storage_key,erased_at FROM stored_files WHERE id=? FOR UPDATE",id);if(rows.isEmpty()||rows.getFirst().get("erased_at")==null)return false;var row=rows.getFirst();if(!id.toString().equals(row.get("storage_key")))throw new IOException("Recorded erased file conflicts with its storage key.");
        if(!storage.existsVerified(id.toString()))return false;
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_legal_holds WHERE user_id=? AND category='files' AND released_at IS NULL AND expires_at>now() AND (resource_id IS NULL OR resource_id=?))",Boolean.class,row.get("owner_id"),id)))return false;
        var at=clock.instant();var entry=journal.ensureFile((UUID)row.get("owner_id"),id,at,at.plus(Duration.ofDays(policy.backupRetentionDays()+2)));storage.eraseVerified(id.toString());sql.update("UPDATE stored_files SET erased_at=clock_timestamp(),sha256='erased' WHERE id=?",id);completions.fileAfterCommit(entry);return true;
    }
}
