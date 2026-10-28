package com.unicarona.privacy.service;

import com.unicarona.shared.repository.AuditRepository;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operational checkout data only; never delete receipts, amounts, ledger or unknown submitted commands. */
@Service
public class PrivacyCheckoutRetention {
    public record Candidate(String kind,UUID id,UUID owner){}
    private static final String ORDER_DUE="""
        m.ticket_url IS NOT NULL OR (
          (m.qr_code IS NOT NULL OR m.qr_base64 IS NOT NULL OR m.challenge_url IS NOT NULL)
          AND (NOT privacy_order_checkout_current(m.payment_id,m.state)
            OR (p.method<>'pix' AND (m.qr_code IS NOT NULL OR m.qr_base64 IS NOT NULL))
            OR (m.challenge_url IS NOT NULL AND (p.method<>'card' OR m.status_detail<>'pending_challenge'))))
        """;
    private static final String PIX_DUE="c.payer_email IS NOT NULL AND (c.status<>'pending' OR p.provider_reference IS NOT NULL OR (c.started_at IS NULL AND c.attempts=0 AND p.expires_at<=clock_timestamp()))";
    private static final String CARD_DUE="c.status='pending' AND c.payload_ciphertext IS NOT NULL AND (p.provider_reference IS NOT NULL OR (c.started_at IS NULL AND c.attempts=0 AND p.expires_at<=clock_timestamp()))";
    private static final String DEBT_DUE="""
        (d.payer_email IS NOT NULL AND NOT privacy_debt_payer_required(d))
        OR ((d.qr_code IS NOT NULL OR d.qr_base64 IS NOT NULL) AND NOT privacy_debt_checkout_current(d))
        OR (d.order_id IS NULL AND d.state='CREATED' AND d.started_at IS NULL AND d.expires_at<=clock_timestamp())
        """;
    private final JdbcTemplate sql;private final PrivacyHoldControlTransactions controls;private final AuditRepository audit;
    public PrivacyCheckoutRetention(JdbcTemplate sql,PrivacyHoldControlTransactions controls,AuditRepository audit){this.sql=sql;this.controls=controls;this.audit=audit;}
    public List<Candidate> candidates(String kind,UUID after,int batch){
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid checkout retention batch.");
        return sql.query("SELECT * FROM (SELECT 'order' AS kind,m.payment_id AS id,p.passenger_id AS owner FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id WHERE "+ORDER_DUE+" UNION ALL SELECT 'pix',c.payment_id,p.passenger_id FROM mercado_pago_pix_commands c JOIN payments p ON p.id=c.payment_id WHERE "+PIX_DUE+" UNION ALL SELECT 'card',c.payment_id,p.passenger_id FROM mercado_pago_card_commands c JOIN payments p ON p.id=c.payment_id WHERE "+CARD_DUE+" UNION ALL SELECT 'debt',d.id,d.passenger_id FROM passenger_debt_payments d WHERE "+DEBT_DUE+") due WHERE (?::text IS NULL OR (kind,id)>(?::text,?::uuid)) ORDER BY kind,id LIMIT ?",(r,n)->new Candidate(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),kind,kind,after,batch);
    }
    @Transactional(rollbackFor=IOException.class) public boolean retire(Candidate candidate,boolean skipLocked)throws IOException {
        controls.requireCurrentForMaintenance();if(sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE"+(skipLocked?" SKIP LOCKED":""),candidate.owner()).isEmpty())return false;
        int changed=switch(candidate.kind()){
            case "order"->sql.update("UPDATE mercado_pago_orders m SET qr_code=CASE WHEN p.method='pix' AND privacy_order_checkout_current(m.payment_id,m.state) THEN m.qr_code END,qr_base64=CASE WHEN p.method='pix' AND privacy_order_checkout_current(m.payment_id,m.state) THEN m.qr_base64 END,ticket_url=NULL,challenge_url=CASE WHEN p.method='card' AND m.status_detail='pending_challenge' AND privacy_order_checkout_current(m.payment_id,m.state) THEN m.challenge_url END FROM payments p WHERE p.id=m.payment_id AND m.payment_id=? AND p.passenger_id=? AND ("+ORDER_DUE+")",candidate.id(),candidate.owner());
            case "pix"->sql.update("UPDATE mercado_pago_pix_commands c SET payer_email=NULL,status=CASE WHEN c.status='pending' THEN CASE WHEN p.provider_reference IS NOT NULL THEN 'done' ELSE 'failed' END ELSE c.status END,last_error_code=CASE WHEN c.status='pending' THEN CASE WHEN p.provider_reference IS NOT NULL THEN NULL ELSE 'PAYMENT_EXPIRED' END ELSE c.last_error_code END FROM payments p WHERE p.id=c.payment_id AND c.payment_id=? AND p.passenger_id=? AND ("+PIX_DUE+")",candidate.id(),candidate.owner());
            case "card"->sql.update("UPDATE mercado_pago_card_commands c SET status=CASE WHEN p.provider_reference IS NOT NULL THEN 'done' ELSE 'failed' END,payload_ciphertext=NULL,last_error_code=CASE WHEN p.provider_reference IS NOT NULL THEN NULL ELSE 'PAYMENT_EXPIRED' END FROM payments p WHERE p.id=c.payment_id AND c.payment_id=? AND p.passenger_id=? AND ("+CARD_DUE+")",candidate.id(),candidate.owner());
            case "debt"->sql.update("UPDATE passenger_debt_payments d SET state=CASE WHEN d.order_id IS NULL AND d.state='CREATED' AND d.started_at IS NULL AND d.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE d.state END WHERE d.id=? AND d.passenger_id=? AND ("+DEBT_DUE+")",candidate.id(),candidate.owner());
            default->throw new IllegalArgumentException("Invalid checkout retention candidate kind.");
        };
        if(changed>0)audit.record(null,"checkout_transients_removed","privacy_retention",null,Map.of("records",changed,"kind",candidate.kind(),"policyVersion","checkout-purpose-v1"));return changed>0;
    }
}
