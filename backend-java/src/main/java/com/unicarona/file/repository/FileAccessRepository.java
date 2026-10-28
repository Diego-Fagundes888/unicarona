package com.unicarona.file.repository;

import java.util.UUID;
import com.unicarona.privacy.service.ParticipantAccessSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FileAccessRepository {
    public static final String LIVE_DOCUMENT_PURPOSE="""
        (f.last_uploaded_at>clock_timestamp()-interval '24 hours' OR
          EXISTS(SELECT 1 FROM driver_applications a WHERE a.user_id=f.owner_id
            AND a.documents_retired_at IS NULL AND a.documents_expires_at>clock_timestamp()
            AND (a.identity_file_id=f.id OR a.license_file_id=f.id)))
        """;
    public record Usage(int count,long bytes) {}
    private final JdbcTemplate sql;
    public FileAccessRepository(JdbcTemplate sql) { this.sql=sql; }
    public Usage usage(UUID owner) {
        return sql.queryForObject("SELECT count(*)::int AS count,coalesce(sum(byte_size),0)::bigint AS bytes FROM stored_files WHERE owner_id=? AND erased_at IS NULL",(r,n) -> new Usage(r.getInt("count"),r.getLong("bytes")),owner);
    }
    public void photo(UUID owner,UUID file) { sql.update("UPDATE users SET photo_file_id=?,updated_at=now() WHERE id=?",file,owner); }
    public void uploaded(UUID file) { sql.update("UPDATE stored_files SET last_uploaded_at=now() WHERE id=?",file); }
    /** Raw documents have either a recent upload purpose or an unexpired application.
     * Case-preserved evidence is accessed through its audited hold endpoint instead. */
    public boolean documentAvailable(UUID file){return Boolean.TRUE.equals(sql.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM stored_files f WHERE f.id=? AND f.deleted_at IS NULL
          AND f.erasure_requested_at IS NULL AND f.erased_at IS NULL AND
        """+LIVE_DOCUMENT_PURPOSE+")",Boolean.class,file));}
    public boolean linkedPhoto(UUID user,UUID owner,UUID file) {
        return !sql.queryForList("""
            SELECT rr.id FROM ride_reservations rr JOIN rides r ON r.id=rr.ride_id
            JOIN universities un ON un.id=r.university_id JOIN users u ON u.id=? WHERE u.photo_file_id=? AND
            ((rr.passenger_id=? AND rr.driver_id=?) OR (rr.driver_id=? AND rr.passenger_id=?))
            AND ("""+ParticipantAccessSql.OPERATIONAL+"""
            )
            AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE b.active AND ((b.by_id=? AND b.other_id=?) OR (b.by_id=? AND b.other_id=?))) LIMIT 1
            """,owner,file,user,owner,user,owner,user,owner,owner,user).isEmpty();
    }
}
