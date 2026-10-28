package com.unicarona.privacy.service;

import com.unicarona.shared.repository.AuditRepository;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes legacy structured location fields that the actual trip flow never consumes.
 * Meeting/region/distance and all contractual/financial records are left intact. */
@Service
public class PrivacyLocationRetentionTransactions {
    private final JdbcTemplate sql;private final AuditRepository audit;
    public PrivacyLocationRetentionTransactions(JdbcTemplate sql,AuditRepository audit){this.sql=sql;this.audit=audit;}
    private static final String CANDIDATES="""
        SELECT rt.id FROM ride_routes rt
        WHERE (rt.origin_address IS NOT NULL OR rt.destination_address IS NOT NULL
          OR rt.origin_lat IS NOT NULL OR rt.origin_lng IS NOT NULL
          OR rt.destination_lat IS NOT NULL OR rt.destination_lng IS NOT NULL)
          AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=rt.driver_id
            AND h.category='location' AND h.released_at IS NULL AND h.expires_at>now()
            AND (h.resource_id IS NULL OR h.resource_id=rt.id))
          AND NOT EXISTS(SELECT 1 FROM rides r JOIN ride_reservations rr ON rr.ride_id=r.id
            JOIN occurrences o ON o.reservation_id=rr.id WHERE r.route_id=rt.id AND o.status<>'resolved')
        ORDER BY rt.created_at,rt.id LIMIT ? FOR UPDATE OF rt
        """;
    @Transactional public int prune(int batch){
        valid(batch);PrivacyRetentionLock.acquire(sql);return clear(batch,true);
    }
    /** A restored copy must be minimized before API/worker readiness. A locked row fails closed
     * through the shared lock timeout instead of being silently skipped at startup. */
    @Transactional public void suppressBeforeReadiness(){
        PrivacyRetentionLock.acquire(sql);while(clear(500,false)>0){/* bounded SQL batches, one startup transaction */}
    }
    private int clear(int batch,boolean skipLocked){
        int changed=sql.update("UPDATE ride_routes SET origin_address=NULL,destination_address=NULL,origin_lat=NULL,origin_lng=NULL,destination_lat=NULL,destination_lng=NULL WHERE id IN ("+CANDIDATES+(skipLocked?" SKIP LOCKED":"")+")",batch);
        if(changed>0)audit.record(null,"unused_precise_location_removed","privacy_retention",null,Map.of("routes",changed,"scope","unused_structured_fields"));
        return changed;
    }
    private static void valid(int batch){if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid location retention batch.");}
}
