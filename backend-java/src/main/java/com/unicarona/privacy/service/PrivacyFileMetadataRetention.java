package com.unicarona.privacy.service;

import com.unicarona.file.storage.*;
import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ninety days after physical erasure, remove unnecessary file metadata only.
 * Individual suppression instructions and case/financial records have separate lifecycles. */
@Service
public class PrivacyFileMetadataRetention {
    public record Candidate(UUID id,UUID owner){}
    private static final String ELIGIBLE="""
        f.erased_at<=clock_timestamp()-interval '90 days' AND f.deleted_at IS NOT NULL
        AND f.erasure_requested_at IS NOT NULL AND f.sha256='erased'
        AND NOT EXISTS(SELECT 1 FROM users u WHERE u.photo_file_id=f.id)
        AND NOT EXISTS(SELECT 1 FROM driver_applications a WHERE a.identity_file_id=f.id OR a.license_file_id=f.id)
        AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.category='files' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=f.id))
        """;
    private final JdbcTemplate sql;private final PrivateFileStorage storage;private final PrivacyHoldControlTransactions controls;private final AuditRepository audit;
    public PrivacyFileMetadataRetention(JdbcTemplate sql,PrivateFileStorage storage,PrivacyHoldControlTransactions controls,AuditRepository audit){this.sql=sql;this.storage=storage;this.controls=controls;this.audit=audit;}
    public List<Candidate> candidates(UUID after,int batch){return sql.query("SELECT f.id,f.owner_id FROM stored_files f WHERE "+ELIGIBLE+" AND (?::uuid IS NULL OR f.id>?::uuid) ORDER BY f.id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),after,after,batch);}
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate)throws IOException {
        controls.requireCurrentForMaintenance();if(sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE SKIP LOCKED",candidate.owner()).isEmpty())return false;if(!PrivateFileLock.tryAcquire(sql,candidate.id()))return false;
        var rows=sql.queryForList("SELECT f.storage_key FROM stored_files f WHERE f.id=? AND f.owner_id=? AND "+ELIGIBLE+" FOR UPDATE OF f",candidate.id(),candidate.owner());if(rows.isEmpty())return false;if(!candidate.id().toString().equals(rows.getFirst().get("storage_key")))throw new IOException("Erased metadata conflicts with its private storage identity.");if(storage.existsVerified(candidate.id().toString()))return false;
        sql.update("DELETE FROM stored_files WHERE id=?",candidate.id());audit.record(null,"erased_file_metadata_removed","privacy_retention",null,Map.of("files",1));return true;
    }
}
