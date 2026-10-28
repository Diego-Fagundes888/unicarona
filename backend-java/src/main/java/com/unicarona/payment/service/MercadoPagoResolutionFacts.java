package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.*;
import com.unicarona.payment.dto.MercadoPagoReviewDto;
import com.unicarona.payment.ledger.AccountKind;
import com.unicarona.shared.ApiException;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Preview and commit share the same evidence; no amount or disposition comes from the browser. */
@Service @ConditionalOnProperty(name="PAYMENT_PROVIDER",havingValue="mercadopago")
public class MercadoPagoResolutionFacts {
    public record Facts(MercadoPagoReviewDto.Resolution preview,UUID owner,UUID driver,UUID receipt,List<String> references) {}
    private final JdbcTemplate sql;private final ObjectMapper json;private final LedgerService ledger;
    public MercadoPagoResolutionFacts(JdbcTemplate sql,ObjectMapper json,LedgerService ledger) {this.sql=sql;this.json=json;this.ledger=ledger;}
    public Facts read(UUID payment) {
        var rows=sql.queryForList("""
            SELECT m.payment_id,m.order_id,m.transaction_id,m.state,m.provider_version,m.refunded_cents,
              m.untracked_refunds,m.refund_history_incomplete,m.chargeback_review,m.review_receipt_id,
              p.passenger_id,q.driver_id,p.external_cents,p.paid_at IS NOT NULL AS paid,
              e.payload::text AS payload,e.status AS receipt_status,e.provider AS receipt_provider
            FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id JOIN ride_requests q ON q.id=p.request_id
            LEFT JOIN webhook_events e ON e.id=m.review_receipt_id WHERE m.payment_id=?
            """,payment);
        ApiException.require(!rows.isEmpty(),"REVIEW_NOT_FOUND","Caso financeiro não encontrado.",404);var m=rows.getFirst();
        UUID owner=(UUID)m.get("passenger_id");var blockers=new ArrayList<String>();
        if(!Boolean.TRUE.equals(m.get("untracked_refunds")) && !Boolean.TRUE.equals(m.get("refund_history_incomplete")) && !Boolean.TRUE.equals(m.get("chargeback_review")))blockers.add("NOT_REQUIRED");
        if(!Boolean.TRUE.equals(m.get("paid")))blockers.add("NO_CONFIRMED_RECEIPT");
        JsonNode proof=json.createObjectNode();try{if(m.get("payload")!=null)proof=json.readTree((String)m.get("payload"));}catch(Exception error){throw new IllegalStateException("Invalid sanitized financial evidence");}
        if(!"processed".equals(m.get("receipt_status")) || !"mercadopago".equals(m.get("receipt_provider")))blockers.add("RECHECK_REQUIRED");
        var observations=sql.queryForList("""
            SELECT o.provider_reference,o.transaction_id,o.amount_cents,o.status,
              EXISTS(SELECT 1 FROM mercado_pago_refunds f WHERE f.provider_reference=o.provider_reference AND f.payment_id=o.payment_id AND f.transfer_id IS NOT NULL) AS linked,
              EXISTS(SELECT 1 FROM mercado_pago_resolved_refunds r WHERE r.provider_reference=o.provider_reference) AS resolved
            FROM mercado_pago_observed_refunds o WHERE o.payment_id=? ORDER BY o.provider_reference
            """,payment);
        var assessments=sql.queryForList("SELECT reversal_cents,held_credit_cents,debt_cents,reason FROM passenger_debt_assessments WHERE payment_id=?",payment);
        var a=assessments.isEmpty()?Map.<String,Object>of():assessments.getFirst();
        long applied=sql.queryForObject("SELECT coalesce(sum(held_credit_cents),0)::bigint FROM mercado_pago_review_resolutions WHERE payment_id=?",Long.class,payment);
        boolean released=Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM passenger_debt_releases WHERE payment_id=?)",Boolean.class,payment));
        long pending=sql.queryForObject("SELECT count(*) FROM mercado_pago_refunds WHERE user_id=? AND status IN ('pending','processing')",Long.class,owner);
        if(pending>0)blockers.add("PENDING_REFUND_COMMAND");
        var references=new ArrayList<String>();long external=0;
        for(var o:observations) {
            String status=(String)o.get("status");boolean linked=Boolean.TRUE.equals(o.get("linked"));
            if(!linked && !Set.of("processed","failed","canceled","cancelled").contains(status))blockers.add("PENDING_EXTERNAL_REFUND");
            if(!linked && "processed".equals(status)) {external+=number(o,"amount_cents");if(!Boolean.TRUE.equals(o.get("resolved")))references.add((String)o.get("provider_reference"));}
        }
        long freshRefunded=0;var freshRefs=new HashSet<String>();
        for(var r:proof.path("refunds"))if("processed".equals(r.path("status").asText())) {freshRefunded+=r.path("cents").asLong();freshRefs.add(r.path("id").asText());}
        if((Boolean.TRUE.equals(m.get("refund_history_incomplete")) && (freshRefunded==0 || freshRefunded<number(m,"refunded_cents"))) || !freshRefs.containsAll(references))blockers.add("REFUND_HISTORY_INCOMPLETE");
        String kind="external_refund";long reversal=number(a,"reversal_cents"),debt=number(a,"debt_cents");
        long held=Math.max(0,number(a,"held_credit_cents")-applied);
        if(Boolean.TRUE.equals(m.get("chargeback_review"))) {
            boolean dispute="UNDER_REVIEW".equals(proof.path("state").asText()) && "charged_back".equals(proof.path("transactionStatus").asText());
            String detail=proof.path("transactionDetail").asText();
            if(dispute && "reimbursed".equals(detail) && detail.equals(proof.path("detail").asText()) && external==0) {
                kind="reimbursed_chargeback";held=0;reversal=0;
                if(!assessments.isEmpty() && !released)blockers.add("DEBT_RELEASE_NOT_CONFIRMED");
            } else if(dispute && "settled".equals(detail) && detail.equals(proof.path("detail").asText()) && external==0) {
                kind="settled_chargeback";
                long linked=sql.queryForObject("SELECT coalesce(sum(amount_cents),0)::bigint FROM mercado_pago_refunds WHERE payment_id=? AND status='approved'",Long.class,payment);
                if(reversal!=number(m,"external_cents")-linked || reversal==0 || !"settled_chargeback".equals(a.get("reason")) || released)blockers.add("ADMINISTRATIVE_DECISION_REQUIRED");
            } else blockers.add("ADMINISTRATIVE_DECISION_REQUIRED");
        } else if(external==0 || reversal!=external || !"external_refund".equals(a.get("reason")) || released)blockers.add("ADMINISTRATIVE_DECISION_REQUIRED");
        if(held>ledger.balance(owner,AccountKind.PASSENGER_CREDIT))blockers.add("CREDIT_BALANCE_MISMATCH");
        long due=Math.max(0,-ledger.balance(owner,AccountKind.PASSENGER_RECEIVABLE));
        var basis=new LinkedHashMap<String,Object>();basis.put("payment",m);basis.put("observations",observations);basis.put("assessment",a);basis.put("heldApplied",applied);basis.put("released",released);basis.put("pendingRefunds",pending);basis.put("dueCents",due);
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(basis)));}catch(Exception error){throw new IllegalStateException("Cannot fingerprint financial case");}
        var unique=List.copyOf(new LinkedHashSet<>(blockers));
        return new Facts(new MercadoPagoReviewDto.Resolution(unique.isEmpty(),kind,hash,reversal,held,debt,due,unique),owner,(UUID)m.get("driver_id"),(UUID)m.get("review_receipt_id"),List.copyOf(references));
    }
    private static long number(Map<String,Object> row,String key) {return row.get(key) instanceof Number n?n.longValue():0;}
}
