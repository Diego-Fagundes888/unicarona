package com.unicarona.privacy.service;

import com.unicarona.file.storage.PrivateFileLock;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retire ordinary application links, not driver approval or case-preserved evidence.
 * Thirty days is an operational policy, not a statutory universal document period. */
@Service
public class PrivacyDriverDocuments {
    public record Candidate(UUID id,UUID owner){}
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final PrivacyHoldRecovery recovery;private final AuditRepository audit;private final NotificationService notices;
    public PrivacyDriverDocuments(JdbcTemplate sql,PrivacyHoldControlTransactions controls,PrivacyHoldRecovery recovery,AuditRepository audit,NotificationService notices){this.sql=sql;this.controls=controls;this.recovery=recovery;this.audit=audit;this.notices=notices;}
    public List<Candidate> candidates(UUID after,int batch){return sql.query("SELECT id,user_id FROM driver_applications WHERE documents_retired_at IS NULL AND documents_expires_at<=clock_timestamp() AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),after,after,batch);}
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();var owners=sql.queryForList("SELECT id,deleted_at FROM users WHERE id=? FOR UPDATE"+(skipLocked?" SKIP LOCKED":""),candidate.owner());if(owners.isEmpty())return false;
        var rows=sql.queryForList("SELECT * FROM driver_applications WHERE id=? AND user_id=? AND documents_retired_at IS NULL AND documents_expires_at<=clock_timestamp() FOR UPDATE",candidate.id(),candidate.owner());if(rows.isEmpty())return false;var row=rows.getFirst();boolean pending="pending".equals(row.get("status"));
        var files=new TreeSet<UUID>();for(String column:List.of("identity_file_id","license_file_id"))if(row.get(column)!=null)files.add((UUID)row.get(column));for(UUID file:files)PrivateFileLock.acquire(sql,file);
        sql.update("UPDATE driver_applications SET identity_file_id=NULL,license_file_id=NULL,documents_retired_at=clock_timestamp(),status=CASE WHEN status='pending' THEN 'expired' ELSE status END WHERE id=?",candidate.id());
        if(pending){
            sql.update("UPDATE vehicles SET status='rejected',updated_at=now() WHERE id=? AND status='pending'",row.get("vehicle_id"));
            sql.update("UPDATE driver_profiles SET status='rejected',approved_at=NULL,updated_at=now() WHERE user_id=? AND status='pending' AND NOT EXISTS(SELECT 1 FROM driver_applications a WHERE a.user_id=? AND a.status='pending' AND a.documents_retired_at IS NULL AND a.documents_expires_at>clock_timestamp())",candidate.owner(),candidate.owner());
            if(owners.getFirst().get("deleted_at")==null)notices.notify(candidate.owner(),"Solicitação de motorista vencida","O prazo de 30 dias terminou antes da análise. Envie uma nova solicitação com os documentos para continuar.","driver",null);
        }
        int prepared=0;for(UUID file:files)prepared+=sql.update("""
            UPDATE stored_files f SET deleted_at=coalesce(deleted_at,now()),erasure_requested_at=now(),erasure_retry_after=now()
            WHERE f.id=? AND f.owner_id=? AND f.erasure_requested_at IS NULL
              AND f.last_uploaded_at<=clock_timestamp()-interval '24 hours'
              AND NOT EXISTS(SELECT 1 FROM users u WHERE u.photo_file_id=f.id)
              AND NOT EXISTS(SELECT 1 FROM driver_applications a WHERE a.identity_file_id=f.id OR a.license_file_id=f.id)
            """,file,candidate.owner());
        audit.record(null,"driver_documents_retired","driver_application",candidate.id(),Map.of("pendingApplicationExpired",pending,"preparedFiles",prepared,"policyVersion",row.get("documents_policy_version")));
        // If a specific hold keeps bytes, its independent snapshot must also retain the
        // ordinary-access retirement flags. Restoring that case is not a public relink.
        recovery.stage();return true;
    }
    public List<UUID> unheldPreparedFiles(int batch){return sql.query("SELECT f.id FROM stored_files f WHERE f.purpose IN ('identity','license') AND f.erasure_requested_at IS NOT NULL AND f.erased_at IS NULL AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=f.owner_id AND h.category='files' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=f.id)) ORDER BY f.id LIMIT ?",(r,n)->r.getObject(1,UUID.class),batch);}
}
