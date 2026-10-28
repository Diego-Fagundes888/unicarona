package com.unicarona.privacy.service;

import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retires a complete, expired zero-net failure; never compacts successful cash. */
@Service
public class PrivacyFailedTransferRetention {
    public record Candidate(UUID id,UUID owner){}
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final PrivacyHoldRecovery recovery;
    public PrivacyFailedTransferRetention(JdbcTemplate sql,PrivacyHoldControlTransactions controls,PrivacyHoldRecovery recovery){this.sql=sql;this.controls=controls;this.recovery=recovery;}
    public List<Candidate> candidates(UUID after,int batch){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid failed transfer retention batch.");
        return sql.query("SELECT t.id,t.user_id FROM transfers t WHERE t.status='failed' AND t.created_at<=now()-interval '1824 days' AND privacy_failed_transfer_expired(t) AND (?::uuid IS NULL OR t.id>?::uuid) ORDER BY t.id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),after,after,batch);
    }
    private List<Map<String,Object>> balances(UUID owner){return sql.queryForList("SELECT a.id,a.kind,a.currency,coalesce(sum(e.cents),0) AS cents FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id WHERE a.owner_id=? GROUP BY a.id ORDER BY a.id",owner);}
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();String lock=skipLocked?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
        if(sql.queryForList("SELECT id FROM users WHERE id=?"+lock,candidate.owner()).isEmpty())return false;
        if(sql.queryForList("SELECT id FROM transfers WHERE id=? AND user_id=?"+lock,candidate.id(),candidate.owner()).isEmpty())return false;
        sql.queryForList("SELECT id FROM ledger_accounts WHERE owner_id=? ORDER BY id FOR UPDATE",candidate.owner());
        if(!Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_failed_transfer_expired(t) FROM transfers t WHERE id=?",Boolean.class,candidate.id())))return false;
        var before=balances(candidate.owner());
        // Every DELETE sees the same original eligibility snapshot. Explicit CTE
        // dependencies also satisfy immediate FKs without opening a partial graph.
        int removed=sql.queryForObject("""
            WITH target AS MATERIALIZED(SELECT id,user_id FROM transfers WHERE id=? AND privacy_failed_transfer_expired(transfers)),
            journals AS MATERIALIZED(SELECT j.id FROM ledger_transactions j,target t WHERE j.event_key IN ('transfer:'||t.id||':requested','transfer:'||t.id||':completed')),
            caches AS(DELETE FROM idempotency_keys k USING target t WHERE k.actor_id=t.user_id AND k.expires_at<=now() AND privacy_unsubmitted_cache_points_to(k,t.id) RETURNING k.key),
            notices AS(DELETE FROM notifications n USING target t WHERE n.user_id=t.user_id AND n.route='wallet' AND n.ref_id=t.id AND (SELECT count(*) FROM caches)>=0 RETURNING n.id),
            audits AS(DELETE FROM audit_logs a USING target t WHERE a.resource_type='transfer' AND a.resource_id=t.id AND (SELECT count(*) FROM notices)>=0 RETURNING a.id),
            receipts AS(DELETE FROM webhook_events w USING target t WHERE lower(w.payload->>'transferId')=t.id::text AND (SELECT count(*) FROM audits)>=0 RETURNING w.id),
            jobs AS(DELETE FROM outbox_jobs j USING target t WHERE j.event_key='transfer:'||t.id AND (SELECT count(*) FROM receipts)>=0 RETURNING j.id),
            postings AS(DELETE FROM ledger_entries e USING journals j WHERE e.transaction_id=j.id AND (SELECT count(*) FROM jobs)>=0 RETURNING e.id),
            headers AS(DELETE FROM ledger_transactions j USING journals original WHERE j.id=original.id AND (SELECT count(*) FROM postings)=4 RETURNING j.id),
            result AS(DELETE FROM transfers t USING target original WHERE t.id=original.id AND (SELECT count(*) FROM headers)=2 RETURNING t.id)
            SELECT count(*)::integer FROM result
            """,Integer.class,candidate.id());
        if(removed!=1||!before.equals(balances(candidate.owner())))throw new IllegalStateException("Neutral financial retirement did not preserve its complete account boundary.");
        recovery.stage();return true;
    }
}
