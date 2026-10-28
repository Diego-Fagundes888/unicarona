package com.unicarona.privacy.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A one-day abandoned-upload allowance is a product policy, not a statutory retention period. */
@Service
public class PrivacyUnusedFiles {
    public record Candidate(UUID id,UUID owner){}
    private static final String UNREFERENCED="""
        NOT EXISTS(SELECT 1 FROM users u WHERE u.photo_file_id=f.id)
        AND NOT EXISTS(SELECT 1 FROM driver_applications a WHERE a.identity_file_id=f.id OR a.license_file_id=f.id)
        """;
    private static final String UNHELD="NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.category='files' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=f.id))";
    private final JdbcTemplate sql;
    public PrivacyUnusedFiles(JdbcTemplate sql){this.sql=sql;}
    public List<Candidate> candidates(int batch){return sql.query("SELECT f.id,f.owner_id FROM stored_files f WHERE f.erasure_requested_at IS NULL AND f.last_uploaded_at<=now()-interval '24 hours' AND "+UNREFERENCED+" AND "+UNHELD+" ORDER BY f.last_uploaded_at,f.id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),batch);}
    @Transactional public boolean retire(Candidate candidate){
        PrivacyRetentionLock.acquire(sql);
        // Every photo replacement and driver application already locks its owner first.
        // Hold this same row through the final reference check, so an upload cannot become
        // linked after the candidate query and before its retirement.
        sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",candidate.owner());
        var rows=sql.queryForList("SELECT f.id FROM stored_files f WHERE f.id=? AND f.owner_id=? AND f.erasure_requested_at IS NULL AND f.last_uploaded_at<=now()-interval '24 hours' AND "+UNREFERENCED+" AND "+UNHELD+" FOR UPDATE OF f",candidate.id(),candidate.owner());
        if(rows.isEmpty())return false;
        sql.update("UPDATE stored_files SET deleted_at=coalesce(deleted_at,now()),erasure_requested_at=now(),erasure_retry_after=now() WHERE id=?",candidate.id());return true;
    }
}
