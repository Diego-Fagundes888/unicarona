package com.unicarona.privacy.service;

import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate a short-lived review narrative from the minimum approval still used by transport.
 * Deadlines derive from original events, so restoring an archive does not renew them. */
@Service
public class PrivacyDriverApplicationRetention {
    public record Candidate(String kind,UUID id,UUID owner){}
    public static final String REVIEW_CURRENT="coalesce(a.reviewed_at,a.created_at)>clock_timestamp()-interval '90 days'";
    // Account replay can update a vehicle today. Its original erasure preparation is
    // the stable purpose boundary; a technical rewrite is not another year of use.
    private static final String VEHICLE_PURPOSE_END="""
        CASE WHEN EXISTS(SELECT 1 FROM users u WHERE u.id=v.owner_id AND u.deleted_at IS NOT NULL)
          THEN coalesce((SELECT min(t.prepared_at) FROM privacy_erasure_tasks t WHERE t.user_id=v.owner_id),
            (SELECT min(e.original_prepared_at) FROM privacy_erasure_events e WHERE e.user_id=v.owner_id),
            (SELECT u.deleted_at FROM users u WHERE u.id=v.owner_id),v.deleted_at,v.updated_at)
          ELSE coalesce(v.deleted_at,v.updated_at) END
        """;
    private static final String APPLICATION_CASE_SAFE="""
        NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=a.user_id
          AND h.category='driver_application' AND h.released_at IS NULL AND h.expires_at>now()
          AND (h.resource_id IS NULL OR h.resource_id=a.id)
          AND NOT EXISTS(SELECT 1 FROM privacy_retained_records p WHERE p.user_id=a.user_id
            AND p.category='driver_application' AND p.resource_id=a.id))
        """;
    private static final String UNUSED_APPLICATION="""
        a.status<>'pending' AND a.documents_retired_at IS NOT NULL
        AND a.identity_file_id IS NULL AND a.license_file_id IS NULL
        AND coalesce(a.reviewed_at,a.created_at)<=clock_timestamp()-interval '365 days'
        AND (a.status<>'approved' OR (
          NOT EXISTS(SELECT 1 FROM vehicles v JOIN users u ON u.id=v.owner_id
            WHERE v.id=a.vehicle_id AND v.status='approved' AND v.deleted_at IS NULL AND u.deleted_at IS NULL)
          AND NOT EXISTS(SELECT 1 FROM rides r WHERE r.vehicle_id=a.vehicle_id)
          AND NOT EXISTS(SELECT 1 FROM routines r WHERE r.vehicle_id=a.vehicle_id)
          AND NOT EXISTS(SELECT 1 FROM vehicles v WHERE v.id=a.vehicle_id AND (%s)>clock_timestamp()-interval '365 days')
        ))
        """.formatted(VEHICLE_PURPOSE_END);
    private static final String UNUSED_VEHICLE="""
        v.status<>'approved' AND greatest(v.created_at,(%s))<=clock_timestamp()-interval '365 days'
        AND NOT EXISTS(SELECT 1 FROM driver_applications a WHERE a.vehicle_id=v.id)
        AND NOT EXISTS(SELECT 1 FROM rides r WHERE r.vehicle_id=v.id)
        AND NOT EXISTS(SELECT 1 FROM routines r WHERE r.vehicle_id=v.id)
        AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=v.owner_id
          AND h.category='vehicle_identity' AND h.released_at IS NULL AND h.expires_at>now()
          AND (h.resource_id IS NULL OR h.resource_id=v.id)
          AND NOT EXISTS(SELECT 1 FROM privacy_retained_records p WHERE p.user_id=v.owner_id
            AND p.category='vehicle_identity' AND p.resource_id=v.id))
        """.formatted(VEHICLE_PURPOSE_END);
    private static final String APPLICATION_DUE="("+APPLICATION_CASE_SAFE+") AND ((NOT ("+REVIEW_CURRENT+") AND (a.decision IS NOT NULL OR a.reviewed_by IS NOT NULL)) OR ("+UNUSED_APPLICATION+"))";
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final AuditRepository audit;
    public PrivacyDriverApplicationRetention(JdbcTemplate sql,PrivacyHoldControlTransactions controls,AuditRepository audit){this.sql=sql;this.controls=controls;this.audit=audit;}
    public List<Candidate> candidates(String afterKind,UUID afterId,int batch){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid driver application retention batch.");
        return sql.query("SELECT * FROM (SELECT 'application' AS kind,a.id,a.user_id AS owner FROM driver_applications a WHERE "+APPLICATION_DUE+" UNION ALL SELECT 'vehicle',v.id,v.owner_id FROM vehicles v WHERE "+UNUSED_VEHICLE+") candidates WHERE (?::text IS NULL OR (kind,id)>(?::text,?::uuid)) ORDER BY kind,id LIMIT ?",(r,n)->new Candidate(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),afterKind,afterKind,afterId,batch);
    }
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();if(sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE"+(skipLocked?" SKIP LOCKED":""),candidate.owner()).isEmpty())return false;
        int changed;
        if("application".equals(candidate.kind())){
            if(sql.queryForList("SELECT id FROM driver_applications a WHERE a.id=? AND a.user_id=? AND "+APPLICATION_DUE+" FOR UPDATE",candidate.id(),candidate.owner()).isEmpty())return false;
            // Case evidence was captured at hold creation and remains private. No ordinary
            // application row is recreated to expose a held narrative after its deadline.
            changed=sql.update("DELETE FROM driver_applications a WHERE a.id=? AND a.user_id=? AND "+UNUSED_APPLICATION,candidate.id(),candidate.owner());
            if(changed==0)changed=sql.update("UPDATE driver_applications a SET decision=NULL,reviewed_by=NULL WHERE a.id=? AND a.user_id=? AND NOT ("+REVIEW_CURRENT+") AND (decision IS NOT NULL OR reviewed_by IS NOT NULL)",candidate.id(),candidate.owner());
        }else if("vehicle".equals(candidate.kind())){
            changed=sql.update("DELETE FROM vehicles v WHERE v.id=? AND v.owner_id=? AND "+UNUSED_VEHICLE,candidate.id(),candidate.owner());
        }else throw new IllegalArgumentException("Invalid driver retention candidate kind.");
        if(changed>0)audit.record(null,"driver_metadata_minimized","privacy_retention",null,Map.of("records",changed,"kind",candidate.kind(),"policyVersion","driver-metadata-v1"));return changed>0;
    }
}
