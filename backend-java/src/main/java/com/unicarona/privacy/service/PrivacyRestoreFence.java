package com.unicarona.privacy.service;

import java.io.IOException;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** After journal expiry, an older database must fail closed rather than recreate a deleted profile. */
@Service
public class PrivacyRestoreFence {
    private final PrivacyBackupCatalog catalog;private final JdbcTemplate sql;
    public PrivacyRestoreFence(PrivacyBackupCatalog catalog,JdbcTemplate sql){this.catalog=catalog;this.sql=sql;}
    public void checkBeforeReplay()throws IOException {
        if(!catalog.enabled())return;
        catalog.locked(()->{
            var inventory=catalog.inventory();long floor=inventory.store().minimumRestorePointEpochMs();
            var row=sql.queryForMap("SELECT dataset_id,observed_at FROM privacy_restore_state WHERE singleton");catalog.bindDataset((java.util.UUID)row.get("dataset_id"));
            var observed=(java.sql.Timestamp)row.get("observed_at");
            if(floor>0&&(observed==null||observed.toInstant().isBefore(Instant.ofEpochMilli(floor))))throw new IOException("Database snapshot predates the independent privacy recovery checkpoint.");return null;
        });
    }
    public void markObserved(){sql.update("UPDATE privacy_restore_state SET observed_at=greatest(coalesce(observed_at,'epoch'::timestamptz),now()) WHERE singleton");}
    static Instant ceiling(Instant at){long millis=at.toEpochMilli();if(at.getNano()%1000000!=0)millis=Math.addExact(millis,1);return Instant.ofEpochMilli(millis);}
    /** Commit the verified suppression horizon before any independent floor can be advanced.
     * A confirmation arriving later must wait for the next committed horizon. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markObservedAtLeast(Instant verified){if(verified==null||verified.isBefore(Instant.EPOCH))throw new IllegalArgumentException("Invalid verified privacy horizon.");sql.update("UPDATE privacy_restore_state SET observed_at=greatest(coalesce(observed_at,'epoch'::timestamptz),clock_timestamp(),?::timestamptz) WHERE singleton",java.sql.Timestamp.from(ceiling(verified)));}
}
