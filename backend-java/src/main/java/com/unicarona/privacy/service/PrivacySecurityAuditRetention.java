package com.unicarona.privacy.service;

import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Original event dates determine expiry; a case keeps an encrypted private copy. */
@Service
public class PrivacySecurityAuditRetention {
    public record Candidate(UUID id,UUID owner){}
    // Broad indexed bound only. The fixed-zone six-calendar-month function is
    // the authoritative deadline, including end-of-month and timezone handling.
    private static final String DUE="a.created_at<=now()-interval '5 months' AND privacy_security_audit_deadline(a)<=clock_timestamp() AND privacy_security_audit_case_safe(a)";
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final AuditRepository audit;private final PrivacyEvidenceCrypto evidence;private final com.fasterxml.jackson.databind.ObjectMapper json;private final PrivacyReadiness readiness;
    public PrivacySecurityAuditRetention(JdbcTemplate sql,PrivacyHoldControlTransactions controls,AuditRepository audit,PrivacyEvidenceCrypto evidence,com.fasterxml.jackson.databind.ObjectMapper json,PrivacyReadiness readiness){this.sql=sql;this.controls=controls;this.audit=audit;this.evidence=evidence;this.json=json;this.readiness=readiness;}
    public List<Candidate> candidates(UUID after,int batch){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid security audit retention batch.");
        return sql.query("SELECT a.id,a.actor_id FROM audit_logs a WHERE "+DUE+" AND (?::uuid IS NULL OR a.id>?::uuid) ORDER BY a.id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),after,after,batch);
    }
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();
        if(candidate.owner()!=null&&sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE"+(skipLocked?" SKIP LOCKED":""),candidate.owner()).isEmpty())return false;
        // Existence alone is not sufficient proof of a usable preserved copy.
        for(var copy:sql.queryForList("SELECT DISTINCT p.id,p.user_id,p.content_ciphertext FROM privacy_legal_holds h JOIN privacy_retained_records p ON p.user_id=h.user_id AND p.category='security_audit' AND p.resource_type='audit_log' AND p.resource_id=? AND p.hold_only WHERE h.category='security_audit' AND (h.resource_id IS NULL OR h.resource_id=?) AND h.released_at IS NULL AND h.expires_at>now()",candidate.id(),candidate.id())){
            try{
                var body=json.readTree(evidence.open(PrivacyEvidenceCrypto.Field.RETAINED_CONTENT,(UUID)copy.get("user_id"),(UUID)copy.get("id"),(String)copy.get("content_ciphertext")));
                if(!candidate.id().toString().equals(body.path("id").asText())||!Objects.equals(copy.get("user_id"),candidate.owner()))throw new IOException();
            }catch(Exception invalid){readiness.begin();throw new IOException("Preserved security audit evidence cannot be authenticated.");}
        }
        int changed=sql.update("DELETE FROM audit_logs a WHERE a.id IN (SELECT a.id FROM audit_logs a WHERE a.id=? AND a.actor_id IS NOT DISTINCT FROM ?::uuid AND "+DUE+" FOR UPDATE"+(skipLocked?" SKIP LOCKED":"")+")",candidate.id(),candidate.owner());
        if(changed>0)audit.record(null,"security_audit_expired","privacy_retention",null,Map.of("records",changed,"policyVersion","security-audit-v1"));return changed>0;
    }
}
