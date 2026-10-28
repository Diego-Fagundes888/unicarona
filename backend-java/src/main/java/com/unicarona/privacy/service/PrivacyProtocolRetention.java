package com.unicarona.privacy.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Removes expired narratives; a dependent financial boundary keeps only its lifecycle witness. */
@Service
public class PrivacyProtocolRetention {
    private final JdbcTemplate sql;private final PrivacyEvidenceCrypto evidence;
    public PrivacyProtocolRetention(JdbcTemplate sql,PrivacyEvidenceCrypto evidence){this.sql=sql;this.evidence=evidence;}
    private List<Map<String,Object>> candidates(int limit){return sql.queryForList("""
        SELECT p.id,p.user_id FROM privacy_requests p
        WHERE p.resolved_at<=now()-interval '5 years'
          AND (p.details_retired_at IS NULL OR NOT EXISTS(SELECT 1 FROM privacy_retained_records r
               WHERE r.user_id=p.user_id AND NOT r.hold_only AND r.category='contract_identity'))
          AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=p.user_id
               AND h.category='privacy_request' AND h.released_at IS NULL AND h.expires_at>now()
               AND (h.resource_id IS NULL OR h.resource_id=p.id))
        ORDER BY p.resolved_at,p.id LIMIT ?
        """,limit);}
    public void expireBatch(int batch){expire(candidates(batch),false);}
    public void expireBeforeReadiness(){var rows=candidates(10001);if(rows.size()>10000)throw new IllegalStateException("Expired privacy protocols exceed the recovery capacity.");expire(rows,true);}
    private void expire(List<Map<String,Object>> rows,boolean recovering){
        // Called under the independent-control lock, before identity expiry. A
        // witness survives the transaction that removes its last dependent copy;
        // only the next checkpoint may remove that witness as well.
        for(var row:rows){UUID owner=(UUID)row.get("user_id"),id=(UUID)row.get("id");
            // Maintenance can retry a busy row; recovery must wait rather than
            // announce readiness while an expired narrative was skipped.
            String lock=recovering?" FOR UPDATE":" FOR UPDATE SKIP LOCKED";
            if(sql.queryForList("SELECT id FROM users WHERE id=?"+lock,owner).isEmpty())continue;
            if(sql.queryForList("SELECT id FROM privacy_requests WHERE id=?"+lock,id).isEmpty())continue;
            sql.update("DELETE FROM privacy_request_responses WHERE request_id=?",id);
            boolean dependent=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_retained_records WHERE user_id=? AND NOT hold_only AND category='contract_identity')",Boolean.class,owner));
            if(dependent){
                String removed=evidence.seal(PrivacyEvidenceCrypto.Field.REQUEST_DETAILS,owner,id,"O conteúdo deste protocolo foi removido após o prazo de retenção. Permanecem apenas os dados mínimos de conclusão necessários à evidência contratual.");
                sql.update("UPDATE privacy_requests SET details_ciphertext=?,details_retired_at=now() WHERE id=? AND details_retired_at IS NULL",removed,id);
            }else sql.update("DELETE FROM privacy_requests WHERE id=?",id);
        }
    }
}
