package com.unicarona.payment.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.ledger.AccountKind;
import com.unicarona.payment.ledger.LedgerCommand;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Receivables never masquerade as spendable credit or rewrite paid rides. */
@Service public class PassengerDebtService {
    public record Summary(long dueCents,long heldCreditCents,boolean purchasesBlocked,UUID activePaymentId) {}
    private final JdbcTemplate sql;
    private final ActorsRepository actors;
    private final LedgerService ledger;
    private final AuditRepository audit;
    private final NotificationService notices;
    public PassengerDebtService(JdbcTemplate sql,ActorsRepository actors,LedgerService ledger,AuditRepository audit,NotificationService notices) {
        this.sql=sql;this.actors=actors;this.ledger=ledger;this.audit=audit;this.notices=notices;
    }
    @Transactional(readOnly=true) public Summary summary(UUID owner) {
        long due=Math.max(0,-ledger.balance(owner,AccountKind.PASSENGER_RECEIVABLE));
        long held=sql.queryForObject("SELECT coalesce(sum(a.held_credit_cents-coalesce(r.cents,0)),0)::bigint FROM passenger_debt_assessments a LEFT JOIN LATERAL (SELECT sum(held_credit_cents) AS cents FROM mercado_pago_review_resolutions WHERE payment_id=a.payment_id) r ON true WHERE a.passenger_id=? AND NOT EXISTS(SELECT 1 FROM passenger_debt_releases WHERE payment_id=a.payment_id)",Long.class,owner);
        var active=sql.queryForList("SELECT id FROM passenger_debt_payments WHERE passenger_id=? AND state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW')",owner);
        return new Summary(due,held,due>0,active.isEmpty()?null:(UUID)active.getFirst().get("id"));
    }
    @Transactional(propagation=Propagation.MANDATORY) public void requirePurchasesAllowed(UUID owner) {
        ApiException.require(!summary(owner).purchasesBlocked(),"PASSENGER_DEBT","Você tem um valor devido por estorno de crédito já utilizado. Regularize a pendência na carteira antes de comprar outra carona.",409);
    }
    /** Called only after identity, amount and provider GET have been verified. */
    @Transactional(propagation=Propagation.MANDATORY) public void assess(UUID payment) { assess(payment,false); }
    @Transactional(propagation=Propagation.MANDATORY) public void assess(UUID payment,boolean settledObservation) {
        var source=sql.queryForMap("SELECT p.passenger_id,p.external_cents,p.paid_at,m.chargeback_review,m.provider_status,m.status_detail,m.refund_history_incomplete FROM payments p JOIN mercado_pago_orders m ON m.payment_id=p.id WHERE p.id=?",payment);
        UUID owner=(UUID)source.get("passenger_id");actors.lockUser(owner);
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM passenger_debt_releases WHERE payment_id=?)",Boolean.class,payment)))return;
        if(source.get("paid_at")==null || Boolean.TRUE.equals(source.get("refund_history_incomplete")))return;
        // An unattached refund may be our own uncertain POST. Do not bill the
        // passenger while its identity or the reserved credit is unresolved.
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM mercado_pago_refunds WHERE user_id=? AND status IN ('pending','processing'))",Boolean.class,owner)))return;
        long linked=sql.queryForObject("SELECT coalesce(sum(amount_cents),0)::bigint FROM mercado_pago_refunds WHERE payment_id=? AND status='approved'",Long.class,payment);
        long external=sql.queryForObject("SELECT coalesce(sum(o.amount_cents),0)::bigint FROM mercado_pago_observed_refunds o WHERE o.payment_id=? AND o.status='processed' AND NOT EXISTS(SELECT 1 FROM mercado_pago_refunds r WHERE r.payment_id=o.payment_id AND r.provider_reference=o.provider_reference AND r.transfer_id IS NOT NULL)",Long.class,payment);
        boolean settled=settledObservation;
        if(settled)external=Math.max(external,((Number)source.get("external_cents")).longValue()-linked);
        long granted=sql.queryForObject("SELECT coalesce(sum(e.cents),0)::bigint FROM ledger_transactions h JOIN ledger_entries e ON e.transaction_id=h.id JOIN ledger_accounts a ON a.id=e.account_id WHERE h.payment_id=? AND a.owner_id=? AND a.kind='passenger_credit' AND e.cents>0",Long.class,payment,owner);
        // Only cancelled-trip credit can become this passenger's debt. A
        // dispute about consumed rides remains a separate financial review.
        long eligible=Math.min(external,Math.max(0,granted-linked));
        var previous=sql.queryForList("SELECT * FROM passenger_debt_assessments WHERE payment_id=? FOR UPDATE",payment);
        long assessed=previous.isEmpty()?0:((Number)previous.getFirst().get("reversal_cents")).longValue();
        if(eligible<=assessed)return;
        long increment=eligible-assessed;
        long reserved=summary(owner).heldCreditCents();
        long available=Math.max(0,ledger.balance(owner,AccountKind.PASSENGER_CREDIT)-reserved);
        long held=Math.min(increment,available),debt=increment-held;
        // Never infer spending from an in-flight refund or a missing ledger.
        long spent=sql.queryForObject("SELECT coalesce(-sum(e.cents),0)::bigint FROM ledger_transactions h JOIN ledger_entries e ON e.transaction_id=h.id JOIN ledger_accounts a ON a.id=e.account_id WHERE a.owner_id=? AND a.kind='passenger_credit' AND e.cents<0 AND h.kind='receipt'",Long.class,owner);
        long existingDebt=summary(owner).dueCents();
        if(debt>Math.max(0,spent-existingDebt))return;
        String reason=settled?"settled_chargeback":"external_refund";
        long totalHeld=held+(previous.isEmpty()?0:((Number)previous.getFirst().get("held_credit_cents")).longValue());
        long totalDebt=debt+(previous.isEmpty()?0:((Number)previous.getFirst().get("debt_cents")).longValue());
        // PostgreSQL validates the proposed INSERT before ON CONFLICT. Both
        // paths must contain cumulative totals satisfying the assessment check.
        sql.update("INSERT INTO passenger_debt_assessments(payment_id,passenger_id,reversal_cents,held_credit_cents,debt_cents,reason) VALUES(?,?,?,?,?,?) ON CONFLICT(payment_id) DO UPDATE SET reversal_cents=excluded.reversal_cents,held_credit_cents=excluded.held_credit_cents,debt_cents=excluded.debt_cents,reason=excluded.reason,updated_at=now()",payment,owner,eligible,totalHeld,totalDebt,reason);
        if(debt>0)ledger.post(LedgerCommand.transfer("debt:"+payment+":"+eligible,"passenger_debt",reason,List.of(new Posting(null,AccountKind.PROVIDER_CLEARING,debt),new Posting(owner,AccountKind.PASSENGER_RECEIVABLE,-debt))));
        audit.record(null,"passenger_debt_assessed","payment",payment,Map.of("reversalCents",eligible,"addedDebtCents",debt,"heldCreditCents",held,"reason",reason));
        if(debt>0)notices.notify(owner,"Valor devido por estorno","Um crédito já utilizado teve o pagamento de origem estornado. Novas compras estão bloqueadas até a regularização. Confira a carteira.","wallet",payment);
    }
    /** A fresh verified GET can resolve a previously settled chargeback. */
    @Transactional(propagation=Propagation.MANDATORY) public void releaseReimbursed(UUID payment) {
        var rows=sql.queryForList("SELECT * FROM passenger_debt_assessments WHERE payment_id=? AND reason='settled_chargeback'",payment);
        if(rows.isEmpty())return;var d=rows.getFirst();UUID owner=(UUID)d.get("passenger_id");actors.lockUser(owner);
        if(Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM passenger_debt_releases WHERE payment_id=?) OR EXISTS(SELECT 1 FROM mercado_pago_observed_refunds WHERE payment_id=? AND status='processed')",Boolean.class,payment,payment)))return;
        long assessed=((Number)d.get("debt_cents")).longValue();
        long restored=sql.queryForObject("SELECT coalesce(sum(held_credit_cents),0)::bigint FROM mercado_pago_review_resolutions WHERE payment_id=? AND kind='settled_chargeback'",Long.class,payment);
        long repaid=sql.queryForObject("SELECT coalesce(sum(cents-reversed_cents),0)::bigint FROM passenger_debt_allocations WHERE payment_id=?",Long.class,payment);
        long waived=Math.max(0,assessed-repaid),credit=Math.min(assessed,repaid);
        var entries=new ArrayList<Posting>();if(assessed+restored>0)entries.add(new Posting(null,AccountKind.PROVIDER_CLEARING,-assessed-restored));
        if(waived>0)entries.add(new Posting(owner,AccountKind.PASSENGER_RECEIVABLE,waived));
        if(credit+restored>0)entries.add(new Posting(owner,AccountKind.PASSENGER_CREDIT,credit+restored));
        if(!entries.isEmpty())ledger.post(LedgerCommand.transfer("debt:"+payment+":reimbursed","passenger_debt_release","Contestação revertida pelo provedor",entries));
        sql.update("INSERT INTO passenger_debt_releases(payment_id,waived_cents,credit_cents,held_restored_cents) VALUES(?,?,?,?)",payment,waived,credit,restored);
        audit.record(null,"passenger_debt_released","payment",payment,Map.of("waivedCents",waived,"creditCents",credit,"heldRestoredCents",restored));
        notices.notify(owner,"Contestação revertida","O provedor reverteu a contestação. O valor devido foi revisto; valores já regularizados voltam como crédito. Confira a carteira.","wallet",payment);
    }
}
