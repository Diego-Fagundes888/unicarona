package com.unicarona.privacy.service;

import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes only the complete never-submitted, never-funded request graph. */
@Service
public class PrivacyUnsubmittedCheckoutRetention {
    public record Candidate(UUID id,UUID passenger,UUID driver){}
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final PrivacyHoldRecovery recovery;
    public PrivacyUnsubmittedCheckoutRetention(JdbcTemplate sql,PrivacyHoldControlTransactions controls,PrivacyHoldRecovery recovery){this.sql=sql;this.controls=controls;this.recovery=recovery;}
    public List<Candidate> candidates(UUID after,int batch){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid unsubmitted checkout retention batch.");
        return sql.query("SELECT q.id,q.passenger_id,q.driver_id FROM ride_requests q WHERE q.created_at<=now()-interval '2160 hours' AND privacy_unsubmitted_request_expired(q) AND (?::uuid IS NULL OR q.id>?::uuid) ORDER BY q.id LIMIT ?",(r,n)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),after,after,batch);
    }
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();String lock=skipLocked?" FOR UPDATE SKIP LOCKED":" FOR UPDATE";
        for(UUID owner:java.util.stream.Stream.of(candidate.passenger(),candidate.driver()).sorted().toList())if(sql.queryForList("SELECT id FROM users WHERE id=?"+lock,owner).isEmpty())return false;
        var rows=sql.queryForList("SELECT id FROM ride_requests WHERE id=? AND passenger_id=? AND driver_id=?"+lock,candidate.id(),candidate.passenger(),candidate.driver());if(rows.isEmpty())return false;
        var payments=sql.queryForList("SELECT id FROM payments WHERE request_id=? ORDER BY id"+lock,candidate.id());
        for(var payment:payments){UUID id=(UUID)payment.get("id");sql.queryForList("SELECT payment_id FROM mercado_pago_card_commands WHERE payment_id=?"+lock,id);sql.queryForList("SELECT payment_id FROM mercado_pago_pix_commands WHERE payment_id=?"+lock,id);}
        if(!Boolean.TRUE.equals(sql.queryForObject("SELECT privacy_unsubmitted_request_expired(q) FROM ride_requests q WHERE id=?",Boolean.class,candidate.id())))return false;
        sql.update("DELETE FROM idempotency_keys k WHERE k.actor_id IN (?,?) AND k.expires_at<=now() AND (privacy_unsubmitted_cache_points_to(k,?::uuid) OR EXISTS(SELECT 1 FROM payments p WHERE p.request_id=? AND privacy_unsubmitted_cache_points_to(k,p.id)))",candidate.passenger(),candidate.driver(),candidate.id(),candidate.id());
        sql.update("DELETE FROM notifications n WHERE n.user_id IN (?,?) AND n.route='requests' AND n.ref_id=? AND NOT EXISTS(SELECT 1 FROM privacy_legal_holds h WHERE h.user_id=n.user_id AND h.category='notifications' AND h.released_at IS NULL AND h.expires_at>now() AND (h.resource_id IS NULL OR h.resource_id=n.id))",candidate.passenger(),candidate.driver(),candidate.id());
        sql.update("DELETE FROM audit_logs WHERE resource_type='request' AND resource_id=?",candidate.id());
        for(var payment:payments){UUID id=(UUID)payment.get("id");sql.update("DELETE FROM outbox_jobs WHERE type IN ('mercadopago_card','mercadopago_pix') AND event_key=type||':'||?::uuid AND payload->>'paymentId'=?::text AND (status<>'processing' OR leased_until<=now())",id,id);sql.update("DELETE FROM mercado_pago_card_commands WHERE payment_id=?",id);sql.update("DELETE FROM mercado_pago_pix_commands WHERE payment_id=?",id);sql.update("DELETE FROM payments WHERE id=?",id);}
        if(sql.update("DELETE FROM ride_requests WHERE id=?",candidate.id())!=1)throw new IllegalStateException("Unsubmitted request retirement lost its locked boundary.");recovery.stage();return true;
    }
}
