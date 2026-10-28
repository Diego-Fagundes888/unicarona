package com.unicarona.payment.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.entity.Transfer;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.provider.MercadoPagoOrdersClient.*;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;

/** Credits are fungible; refunds are bounded by available credit and original credited receipts. */
public class MercadoPagoRefundTransactions {
  public record Command(
      UUID id,
      UUID payment,
      UUID transfer,
      UUID owner,
      String order,
      String transaction,
      int cents,
      String reference,
      int attempts,
      int externalCents,
      int previouslyRefunded) {}

  private final PassengerDebtService debts;
  private final JdbcTemplate sql;
  private final ActorsRepository actors;
  private final LedgerService ledger;
  private final NotificationService notices;
  private final AuditRepository audit;

  public MercadoPagoRefundTransactions(
      JdbcTemplate sql,
      ActorsRepository actors,
      LedgerService ledger,
      NotificationService notices,
      AuditRepository audit,
      PassengerDebtService debts) {
    this.debts = debts;
    this.sql = sql;
    this.actors = actors;
    this.ledger = ledger;
    this.notices = notices;
    this.audit = audit;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void plan(Transfer transfer) {
    UUID owner = transfer.getUserId();
    ApiException.require(
        !Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id WHERE p.passenger_id=? AND (m.untracked_refunds OR m.chargeback_review))",
                Boolean.class,
                owner)),
        "REFUND_REQUIRES_REVIEW",
        "Há uma movimentação financeira em análise. Procure o suporte antes de usar ou devolver esse saldo.",
        409);
    var sources =
        sql.queryForList(
            """
            SELECT p.id,m.transaction_id,
              least(greatest(0,p.external_cents-greatest(coalesce(allocated.cents,0),m.refunded_cents)),
                    greatest(0,coalesce(credited.cents,0)-coalesce(allocated.cents,0))) AS capacity
            FROM payments p JOIN mercado_pago_orders m ON m.payment_id=p.id
            LEFT JOIN LATERAL (SELECT sum(r.amount_cents)::bigint AS cents FROM mercado_pago_refunds r WHERE r.payment_id=p.id AND r.status<>'failed') allocated ON true
            LEFT JOIN LATERAL (SELECT sum(e.cents)::bigint AS cents FROM ledger_transactions h JOIN ledger_entries e ON e.transaction_id=h.id JOIN ledger_accounts a ON a.id=e.account_id WHERE h.payment_id=p.id AND a.owner_id=p.passenger_id AND a.kind='passenger_credit' AND e.cents>0) credited ON true
            WHERE p.passenger_id=? AND p.provider='mercadopago' AND p.paid_at>=now()-interval '180 days'
              AND p.external_cents>0 AND m.state IN ('APPROVED','PARTIALLY_REFUNDED')
              AND m.transaction_id IS NOT NULL AND NOT m.untracked_refunds AND NOT m.chargeback_review
            ORDER BY p.paid_at,p.id
            """,
            owner);
    long capacity =
        sources.stream().mapToLong(row -> ((Number) row.get("capacity")).longValue()).sum();
    ApiException.require(
        capacity >= transfer.getCents(),
        "REFUND_SOURCE_UNAVAILABLE",
        "Esse saldo não tem recebimentos elegíveis suficientes para devolução pelo Mercado Pago. Ele permanece disponível; procure o suporte.",
        409);
    int remaining = transfer.getCents();
    for (var source : sources) {
      int cents = (int) Math.min(remaining, ((Number) source.get("capacity")).longValue());
      if (cents == 0) continue;
      String transaction = (String) source.get("transaction_id");
      ApiException.require(
          transaction.matches("PAY[A-Za-z0-9]{10,64}"),
          "REFUND_SOURCE_UNAVAILABLE",
          "Recebimento exige revisão financeira.",
          409);
      sql.update(
          "INSERT INTO mercado_pago_refunds(payment_id,user_id,amount_cents,reason,transfer_id,transaction_id) VALUES(?,?,?,'Devolução solicitada do saldo de crédito',?,?)",
          source.get("id"),
          owner,
          cents,
          transfer.getId(),
          transaction);
      remaining -= cents;
      if (remaining == 0) break;
    }
    sql.update(
        "UPDATE transfers SET provider_reference=? WHERE id=? AND provider_reference IS NULL",
        "MP_REFUND_BATCH:" + transfer.getId(),
        transfer.getId());
  }

  @Transactional
  public Optional<Command> start(UUID id) {
    UUID owner =
        sql.queryForObject("SELECT user_id FROM mercado_pago_refunds WHERE id=?", UUID.class, id);
    actors.lockUser(owner);
    var rows =
        sql.queryForList(
            "SELECT r.*,p.external_cents,m.order_id,m.refunded_cents,m.chargeback_review,m.untracked_refunds FROM mercado_pago_refunds r JOIN payments p ON p.id=r.payment_id JOIN mercado_pago_orders m ON m.payment_id=p.id WHERE r.id=? AND r.transfer_id IS NOT NULL AND r.status IN ('pending','processing') FOR UPDATE OF r",
            id);
    if (rows.isEmpty()) return Optional.empty();
    var r = rows.getFirst();
    // Do not start a new refund against disputed funds. Prior uncertain sends still need recovery.
    if (((Number) r.get("attempts")).intValue() == 0
        && (Boolean.TRUE.equals(r.get("chargeback_review"))
            || Boolean.TRUE.equals(r.get("untracked_refunds")))) return Optional.empty();
    sql.update(
        "UPDATE mercado_pago_refunds SET status='processing',attempts=attempts+1,updated_at=now() WHERE id=?",
        id);
    return Optional.of(
        new Command(
            id,
            (UUID) r.get("payment_id"),
            (UUID) r.get("transfer_id"),
            owner,
            (String) r.get("order_id"),
            (String) r.get("transaction_id"),
            ((Number) r.get("amount_cents")).intValue(),
            (String) r.get("provider_reference"),
            ((Number) r.get("attempts")).intValue() + 1,
            ((Number) r.get("external_cents")).intValue(),
            ((Number) r.get("refunded_cents")).intValue()));
  }

  @Transactional(readOnly = true)
  public Set<String> linkedReferences(Command command) {
    return Set.copyOf(
        sql.queryForList(
            "SELECT provider_reference FROM mercado_pago_refunds WHERE payment_id=? AND user_id=? AND id<>? AND provider_reference IS NOT NULL",
            String.class,
            command.payment(),
            command.owner(),
            command.id()));
  }

  @Transactional
  public void attach(Command command, Refund refund) {
    actors.lockUser(command.owner());
    var row =
        sql.queryForMap("SELECT * FROM mercado_pago_refunds WHERE id=? FOR UPDATE", command.id());
    ApiException.require(
        command.transaction().equals(refund.transactionId())
            && command.cents() == refund.cents()
            && (row.get("provider_reference") == null
                || refund.id().equals(row.get("provider_reference"))),
        "REFUND_MISMATCH",
        "Devolução externa incompatível.",
        502);
    sql.update(
        "UPDATE mercado_pago_refunds SET provider_reference=coalesce(provider_reference,?),updated_at=now() WHERE id=?",
        refund.id(),
        command.id());
  }

  @Transactional
  public void error(Command command, String code) {
    actors.lockUser(command.owner());
    // A newer attempt or an attached refund may exist after this call left the database.
    // Only a still-unattached first POST rejection can release the reserved credit.
    var persisted =
        sql.queryForMap(
            "SELECT attempts,provider_reference FROM mercado_pago_refunds WHERE id=? FOR UPDATE",
            command.id());
    boolean definitive =
        command.reference() == null
            && command.attempts() == 1
            && persisted.get("provider_reference") == null
            && ((Number) persisted.get("attempts")).intValue() == 1
            && "PAYMENT_PROVIDER_REJECTED".equals(code);
    sql.update(
        "UPDATE mercado_pago_refunds SET status=CASE WHEN ? THEN 'failed' ELSE status END,last_error_code=?,completed_at=CASE WHEN ? THEN now() ELSE completed_at END,updated_at=now() WHERE id=? AND status IN ('pending','processing')",
        definitive,
        code,
        definitive,
        command.id());
    if (definitive) complete(command.transfer());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void observeChargeback(Snapshot snapshot) {
    if (!"charged_back".equals(snapshot.status())
        && !"charged_back".equals(snapshot.statusDetail())) return;
    if (verifiedDispute(snapshot, "reimbursed")) {
      debts.releaseReimbursed(snapshot.paymentId());
      return;
    }
    if (verifiedDispute(snapshot, "settled")
        && Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM mercado_pago_review_resolutions r WHERE r.payment_id=? AND r.kind='settled_chargeback') AND NOT EXISTS(SELECT 1 FROM passenger_debt_releases WHERE payment_id=?)",
                Boolean.class,
                snapshot.paymentId(),
                snapshot.paymentId()))) return;
    var changed =
        sql.queryForList(
            "UPDATE mercado_pago_orders SET chargeback_review=true,updated_at=now() WHERE payment_id=? AND NOT chargeback_review RETURNING payment_id",
            UUID.class,
            snapshot.paymentId());
    if (!changed.isEmpty())
      audit.record(
          null,
          "mercadopago_chargeback_review",
          "payment",
          snapshot.paymentId(),
          Map.of("reviewRequired", true));
    debts.assess(snapshot.paymentId(), verifiedDispute(snapshot, "settled"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void observe(Snapshot snapshot) {
    UUID owner =
        sql.queryForObject(
            "SELECT passenger_id FROM payments WHERE id=?", UUID.class, snapshot.paymentId());
    actors.lockUser(owner);
    var order =
        sql.queryForMap(
            "SELECT transaction_id,untracked_refunds,refund_history_incomplete FROM mercado_pago_orders WHERE payment_id=?",
            snapshot.paymentId());
    var changed = new HashSet<UUID>();
    for (var refund : snapshot.refunds()) {
      ApiException.require(
          refund.transactionId().equals(order.get("transaction_id")),
          "REFUND_MISMATCH",
          "Devolução externa incompatível.",
          502);
      var previous =
          sql.queryForList(
              "SELECT * FROM mercado_pago_observed_refunds WHERE provider_reference=? FOR UPDATE",
              refund.id());
      String status = refund.status();
      if (!previous.isEmpty()) {
        var observed = previous.getFirst();
        ApiException.require(
            snapshot.paymentId().equals(observed.get("payment_id"))
                && refund.transactionId().equals(observed.get("transaction_id"))
                && refund.cents() == ((Number) observed.get("amount_cents")).intValue(),
            "REFUND_MISMATCH",
            "Devolução externa incompatível.",
            502);
        String current = (String) observed.get("status");
        if (terminalExternal(current)) {
          ApiException.require(
              !terminalExternal(status) || current.equals(status),
              "REFUND_STATE_CONFLICT",
              "Devolução exige conciliação financeira.",
              409);
          status = current;
        }
        sql.update(
            "UPDATE mercado_pago_observed_refunds SET status=?,last_observed_at=now() WHERE provider_reference=?",
            status,
            refund.id());
      } else
        sql.update(
            "INSERT INTO mercado_pago_observed_refunds(provider_reference,payment_id,transaction_id,amount_cents,status) VALUES(?,?,?,?,?)",
            refund.id(),
            snapshot.paymentId(),
            refund.transactionId(),
            refund.cents(),
            status);
    }
    // Revisit persisted observations after an uncertain POST is linked to its command.
    var observations =
        sql.queryForList(
            "SELECT * FROM mercado_pago_observed_refunds WHERE payment_id=? ORDER BY provider_reference",
            snapshot.paymentId());
    boolean untracked = Boolean.TRUE.equals(order.get("refund_history_incomplete"));
    int refunded = 0;
    for (var observed : observations) {
      String status = (String) observed.get("status");
      int cents = ((Number) observed.get("amount_cents")).intValue();
      if ("processed".equals(status)) refunded = Math.addExact(refunded, cents);
      var matches =
          sql.queryForList(
              "SELECT * FROM mercado_pago_refunds WHERE payment_id=? AND provider_reference=? AND transfer_id IS NOT NULL FOR UPDATE",
              snapshot.paymentId(),
              observed.get("provider_reference"));
      if (matches.isEmpty()) {
        if (!Set.of("failed", "canceled", "cancelled").contains(status)
            && !Boolean.TRUE.equals(
                sql.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM mercado_pago_resolved_refunds WHERE provider_reference=?)",
                    Boolean.class,
                    observed.get("provider_reference")))) untracked = true;
        continue;
      }
      var row = matches.getFirst();
      ApiException.require(
          observed.get("transaction_id").equals(row.get("transaction_id"))
              && cents == ((Number) row.get("amount_cents")).intValue(),
          "REFUND_MISMATCH",
          "Devolução externa incompatível.",
          502);
      String result =
          "processed".equals(status)
              ? "approved"
              : Set.of("failed", "canceled", "cancelled").contains(status)
                  ? "failed"
                  : "processing";
      String current = (String) row.get("status");
      if (Set.of("approved", "failed").contains(current)) {
        ApiException.require(
            current.equals(result),
            "REFUND_STATE_CONFLICT",
            "Devolução exige conciliação financeira.",
            409);
        continue;
      }
      if (current.equals(result) && row.get("last_error_code") == null) continue;
      sql.update(
          "UPDATE mercado_pago_refunds SET status=?,completed_at=CASE WHEN ? IN ('approved','failed') THEN now() ELSE completed_at END,last_error_code=NULL,updated_at=now() WHERE id=?",
          result,
          result,
          row.get("id"));
      changed.add((UUID) row.get("transfer_id"));
    }
    ApiException.require(
        refunded <= snapshot.amountCents(),
        "REFUND_MISMATCH",
        "Devolução externa incompatível.",
        502);
    sql.update(
        "UPDATE mercado_pago_orders SET refunded_cents=greatest(refunded_cents,?),untracked_refunds=? WHERE payment_id=?",
        refunded,
        untracked,
        snapshot.paymentId());
    if (untracked && !Boolean.TRUE.equals(order.get("untracked_refunds")))
      audit.record(
          null,
          "mercadopago_untracked_refund",
          "payment",
          snapshot.paymentId(),
          Map.of("refundedCents", refunded));
    for (var id : changed) complete(id);
    debts.assess(snapshot.paymentId(), verifiedDispute(snapshot, "settled"));
  }

  static boolean verifiedDispute(Snapshot snapshot, String detail) {
    return "charged_back".equals(snapshot.status())
        && detail.equals(snapshot.statusDetail())
        && "charged_back".equals(snapshot.transactionStatus())
        && detail.equals(snapshot.transactionDetail());
  }

  private static boolean terminalExternal(String status) {
    return Set.of("processed", "failed", "canceled", "cancelled").contains(status);
  }

  private void complete(UUID id) {
    var transfer = sql.queryForMap("SELECT * FROM transfers WHERE id=? FOR UPDATE", id);
    if (!"pending".equals(transfer.get("status"))) return;
    var totals =
        sql.queryForMap(
            "SELECT coalesce(sum(amount_cents) FILTER(WHERE status='approved'),0) AS approved,coalesce(sum(amount_cents) FILTER(WHERE status='failed'),0) AS failed,count(*) FILTER(WHERE status IN ('pending','processing')) AS pending FROM mercado_pago_refunds WHERE transfer_id=?",
            id);
    if (((Number) totals.get("pending")).intValue() != 0) {
      sql.update("UPDATE transfers SET updated_at=now() WHERE id=?", id);
      return;
    }
    int cents = ((Number) transfer.get("cents")).intValue(),
        approved = ((Number) totals.get("approved")).intValue(),
        failed = ((Number) totals.get("failed")).intValue();
    ApiException.require(
        approved + failed == cents,
        "REFUND_MISMATCH",
        "Devolução exige conciliação financeira.",
        409);
    UUID owner = (UUID) transfer.get("user_id");
    String status =
        approved == cents ? "approved" : failed == cents ? "failed" : "partially_refunded";
    var entries = new ArrayList<Posting>();
    entries.add(new Posting(owner, AccountKind.REFUND_PENDING, -cents));
    if (approved > 0) entries.add(new Posting(owner, AccountKind.REFUNDED, approved));
    if (failed > 0) entries.add(new Posting(owner, AccountKind.PASSENGER_CREDIT, failed));
    ledger.post(
        LedgerCommand.transfer(
            "transfer:" + id + ":completed",
            "partially_refunded".equals(status)
                ? "refund_partial"
                : "approved".equals(status) ? "refund" : "transfer_failed",
            "Devolução conferida no Mercado Pago; saldo não devolvido restaurado",
            entries));
    sql.update("UPDATE transfers SET status=?,updated_at=now() WHERE id=?", status, id);
    audit.record(
        null,
        "mercadopago_refund_completed",
        "transfer",
        id,
        Map.of("status", status, "refundedCents", approved, "restoredCents", failed));
    notices.notify(
        owner,
        approved == cents
            ? "Devolução concluída"
            : approved == 0 ? "Devolução não concluída" : "Devolução parcial concluída",
        failed == 0
            ? "O provedor confirmou a devolução ao meio de pagamento original."
            : "A parte não devolvida voltou para seu crédito disponível.",
        "wallet",
        id);
  }
}
