package com.unicarona.payment.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.provider.MercadoPagoOrdersClient.Snapshot;
import com.unicarona.payment.provider.MercadoPagoStatus;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No ride, reservation, commission or interest is created by a debt repayment. */
@Service
public class DebtPaymentTransactions {
  public record Detail(
      UUID id,
      int amountCents,
      String state,
      String statusDetail,
      String qrCode,
      String qrBase64,
      String expiresAt) {}

  public record Command(UUID id, int cents, String email) {}

  public record Claim(UUID id, UUID lease) {}

  private final JdbcTemplate sql;
  private final ActorsRepository actors;
  private final PassengerDebtService debts;
  private final LedgerService ledger;
  private final IdempotencyExecutor idempotency;
  private final OutboxRepository jobs;
  private final AuditRepository audit;
  private final NotificationService notices;
  private final WebhookReceipts receipts;

  public DebtPaymentTransactions(
      JdbcTemplate sql,
      ActorsRepository actors,
      PassengerDebtService debts,
      LedgerService ledger,
      IdempotencyExecutor idempotency,
      OutboxRepository jobs,
      AuditRepository audit,
      NotificationService notices,
      WebhookReceipts receipts) {
    this.sql = sql;
    this.actors = actors;
    this.debts = debts;
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.jobs = jobs;
    this.audit = audit;
    this.notices = notices;
    this.receipts = receipts;
  }

  @Transactional
  public UUID prepare(Actor actor, String key) {
    return idempotency.execute(
        actor.id(),
        key,
        "debt:pix",
        Map.of(),
        UUID.class,
        () -> {
          actors.lockUser(actor.id());
          var pending =
              sql.queryForList(
                  "SELECT id FROM passenger_debt_payments WHERE passenger_id=? AND state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW')",
                  actor.id());
          if (!pending.isEmpty()) return (UUID) pending.getFirst().get("id");
          long due = debts.summary(actor.id()).dueCents();
          ApiException.require(
              due > 0, "NO_PASSENGER_DEBT", "Você não tem valor devido para regularizar.", 409);
          int amount = (int) Math.min(due, Integer.MAX_VALUE);
          UUID id =
              sql.queryForObject(
                  "INSERT INTO passenger_debt_payments(passenger_id,amount_cents,payer_email) SELECT id,?,email FROM users WHERE id=? RETURNING id",
                  UUID.class,
                  amount,
                  actor.id());
          jobs.enqueue(
              "mercadopago_debt_pix", "mercadopago_debt_pix:" + id, Map.of("paymentId", id));
          audit.record(
              actor.id(),
              "passenger_debt_checkout",
              "debt_payment",
              id,
              Map.of("amountCents", amount));
          return id;
        });
  }

  @Transactional
  public Optional<Command> start(UUID id) {
    var owner =
        sql.queryForObject(
            "SELECT passenger_id FROM passenger_debt_payments WHERE id=?", UUID.class, id);
    actors.lockUser(owner);
    var p = sql.queryForMap("SELECT * FROM passenger_debt_payments WHERE id=? FOR UPDATE", id);
    if (p.get("order_id") != null || !"CREATED".equals(p.get("state"))) return Optional.empty();
    if (p.get("started_at") == null
        && Boolean.TRUE.equals(
            sql.queryForObject(
                "SELECT expires_at<=clock_timestamp() FROM passenger_debt_payments WHERE id=?",
                Boolean.class,
                id))) {
      sql.update(
          "UPDATE passenger_debt_payments SET state='EXPIRED',status_detail='checkout_expired',updated_at=now() WHERE id=?",
          id);
      return Optional.empty();
    }
    if (p.get("started_at") == null
        && debts.summary(owner).dueCents() < ((Number) p.get("amount_cents")).longValue()) {
      sql.update(
          "UPDATE passenger_debt_payments SET state='CANCELLED',status_detail='debt_changed',updated_at=now() WHERE id=?",
          id);
      return Optional.empty();
    }
    sql.update(
        "UPDATE passenger_debt_payments SET started_at=coalesce(started_at,now()),updated_at=now() WHERE id=?",
        id);
    return Optional.of(
        new Command(
            id, ((Number) p.get("amount_cents")).intValue(), (String) p.get("payer_email")));
  }

  @Transactional
  public void failed(UUID id, String code) {
    sql.update(
        "UPDATE passenger_debt_payments SET last_error_code=?,updated_at=now() WHERE id=?",
        code,
        id);
  }

  @Transactional(readOnly = true)
  public Detail detail(UUID owner, UUID id) {
    var rows =
        sql.query(
            "SELECT d.*,CASE WHEN privacy_debt_checkout_current(d) THEN d.qr_code END AS visible_qr_code,CASE WHEN privacy_debt_checkout_current(d) THEN d.qr_base64 END AS visible_qr_base64 FROM passenger_debt_payments d WHERE id=? AND passenger_id=?",
            (r, n) ->
                new Detail(
                    id,
                    r.getInt("amount_cents"),
                    r.getString("state"),
                    r.getString("status_detail"),
                    r.getString("visible_qr_code"),
                    r.getString("visible_qr_base64"),
                    ApiDates.timestamp(r.getTimestamp("expires_at").toInstant())),
            id,
            owner);
    ApiException.require(!rows.isEmpty(), "PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404);
    return rows.getFirst();
  }

  @Transactional
  public PaymentDto.Received apply(Snapshot snapshot, UUID receipt) {
    UUID id = snapshot.paymentId();
    UUID owner =
        sql.queryForObject(
            "SELECT passenger_id FROM passenger_debt_payments WHERE id=?", UUID.class, id);
    actors.lockUser(owner);
    var p = sql.queryForMap("SELECT * FROM passenger_debt_payments WHERE id=? FOR UPDATE", id);
    ApiException.require(
        ((Number) p.get("amount_cents")).intValue() == snapshot.amountCents()
            && (p.get("order_id") == null || snapshot.orderId().equals(p.get("order_id"))),
        "WEBHOOK_MISMATCH",
        "Pagamento externo incompatível.",
        422);
    ApiException.require(
        p.get("transaction_id") == null
            || snapshot.transactionId() == null
            || snapshot.transactionId().equals(p.get("transaction_id")),
        "WEBHOOK_MISMATCH",
        "Transação externa incompatível.",
        422);
    for (var refund : snapshot.refunds())
      ApiException.require(
          refund.transactionId().equals(snapshot.transactionId()),
          "WEBHOOK_MISMATCH",
          "Devolução externa incompatível.",
          422);
    if (snapshot.version() > 0
        && snapshot.version() < ((Number) p.get("provider_version")).longValue()) {
      receipts.processed(receipt);
      return new PaymentDto.Received(true, true);
    }
    boolean paid = p.get("paid_at") != null;
    boolean duplicate = paid;
    boolean previouslyRefunded = ((Number) p.get("refunded_cents")).longValue() > 0;
    long refunded =
        snapshot.refunds().stream()
            .filter(r -> "processed".equals(r.status()))
            .mapToLong(r -> r.cents())
            .sum();
    if (snapshot.state() == MercadoPagoStatus.REFUNDED
        || MercadoPagoRefundTransactions.verifiedDispute(snapshot, "settled"))
      refunded = snapshot.amountCents();
    ApiException.require(
        refunded <= snapshot.amountCents(),
        "WEBHOOK_MISMATCH",
        "Devolução externa incompatível.",
        422);
    // A first GET can already contain a processed partial refund. Recognize
    // the original receipt and its reversal atomically, leaving only net
    // money to settle the debt. Missing refund history never clears debt.
    boolean partialReceipt =
        snapshot.state() == MercadoPagoStatus.PARTIALLY_REFUNDED
            && refunded > 0
            && refunded < snapshot.amountCents();
    if (!paid
        && !previouslyRefunded
        && (snapshot.state() == MercadoPagoStatus.APPROVED || partialReceipt)) {
      long amount = snapshot.amountCents(), due = Math.min(amount, debts.summary(owner).dueCents());
      var entries = new ArrayList<Posting>();
      entries.add(new Posting(null, AccountKind.PROVIDER_CLEARING, -amount));
      if (due > 0) entries.add(new Posting(owner, AccountKind.PASSENGER_RECEIVABLE, due));
      if (amount > due) entries.add(new Posting(owner, AccountKind.PASSENGER_CREDIT, amount - due));
      ledger.post(
          LedgerCommand.transfer(
              "debt-payment:" + id + ":confirmed",
              "debt_repayment",
              "Regularização de valor devido",
              entries));
      long remaining = due;
      for (var source :
          sql.queryForList(
              "SELECT d.payment_id,d.debt_cents-coalesce((SELECT sum(a.cents-a.reversed_cents) FROM passenger_debt_allocations a WHERE a.payment_id=d.payment_id),0)-coalesce((SELECT r.waived_cents FROM passenger_debt_releases r WHERE r.payment_id=d.payment_id),0) AS due FROM passenger_debt_assessments d WHERE passenger_id=? ORDER BY created_at,payment_id",
              owner)) {
        long part = Math.min(remaining, Math.max(0, ((Number) source.get("due")).longValue()));
        if (part > 0)
          sql.update(
              "INSERT INTO passenger_debt_allocations(repayment_id,payment_id,cents) VALUES(?,?,?)",
              id,
              source.get("payment_id"),
              part);
        remaining -= part;
        if (remaining == 0) break;
      }
      sql.update("UPDATE passenger_debt_payments SET paid_at=now() WHERE id=?", id);
      paid = true;
      audit.record(
          null,
          "passenger_debt_paid",
          "debt_payment",
          id,
          Map.of("amountCents", amount, "debtPaidCents", due));
    }
    long previous = ((Number) p.get("refunded_cents")).longValue();
    if (paid && refunded > previous) {
      long delta = refunded - previous, unwind = delta;
      for (var allocation :
          sql.queryForList(
              "SELECT payment_id,cents-reversed_cents AS available FROM passenger_debt_allocations WHERE repayment_id=? ORDER BY payment_id FOR UPDATE",
              id)) {
        long part = Math.min(unwind, ((Number) allocation.get("available")).longValue());
        if (part > 0)
          sql.update(
              "UPDATE passenger_debt_allocations SET reversed_cents=reversed_cents+? WHERE repayment_id=? AND payment_id=?",
              part,
              id,
              allocation.get("payment_id"));
        unwind -= part;
        if (unwind == 0) break;
      }
      // Excess repayment credit is revoked first. Spent credit becomes a
      // new receivable; an external refund can never create free credit.
      long available = Math.max(0, ledger.balance(owner, AccountKind.PASSENGER_CREDIT));
      long credit = Math.min(unwind, available), newDebt = delta - credit;
      var entries = new ArrayList<Posting>();
      entries.add(new Posting(null, AccountKind.PROVIDER_CLEARING, delta));
      if (newDebt > 0) entries.add(new Posting(owner, AccountKind.PASSENGER_RECEIVABLE, -newDebt));
      if (credit > 0) entries.add(new Posting(owner, AccountKind.PASSENGER_CREDIT, -credit));
      ledger.post(
          LedgerCommand.transfer(
              "debt-payment:" + id + ":reversed:" + refunded,
              "debt_repayment_reversal",
              "Estorno da regularização",
              entries));
      audit.record(
          null,
          "passenger_debt_reopened",
          "debt_payment",
          id,
          Map.of("refundedCents", refunded, "addedDebtCents", newDebt));
      if (newDebt > 0)
        notices.notify(
            owner,
            "Regularização estornada",
            "O pagamento de regularização foi estornado. Confira o valor devido na carteira.",
            "wallet",
            id);
    }
    boolean paidRegression =
        paid
            && Set.of(
                    MercadoPagoStatus.CREATED,
                    MercadoPagoStatus.PENDING,
                    MercadoPagoStatus.PROCESSING,
                    MercadoPagoStatus.REJECTED,
                    MercadoPagoStatus.CANCELLED,
                    MercadoPagoStatus.EXPIRED)
                .contains(snapshot.state());
    String state =
        paidRegression || (!paid && previouslyRefunded)
            ? (String) p.get("state")
            : snapshot.state().name();
    if (Math.max(previous, refunded) > 0)
      state =
          Math.max(previous, refunded) == snapshot.amountCents()
              ? "REFUNDED"
              : "PARTIALLY_REFUNDED";
    if (!paid && snapshot.state() == MercadoPagoStatus.PARTIALLY_REFUNDED && refunded == 0)
      state = "UNDER_REVIEW";
    sql.update(
        "UPDATE passenger_debt_payments SET order_id=?,state=?,provider_version=greatest(provider_version,?),status_detail=?,qr_code=coalesce(?,qr_code),qr_base64=coalesce(?,qr_base64),refunded_cents=greatest(refunded_cents,?),last_error_code=NULL,next_check_at=now()+CASE WHEN ? IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW') THEN interval '1 minute' ELSE interval '6 hours' END,updated_at=now() WHERE id=?",
        snapshot.orderId(),
        state,
        snapshot.version(),
        snapshot.statusDetail(),
        snapshot.qrCode(),
        snapshot.qrBase64(),
        refunded,
        state,
        id);
    sql.update(
        "UPDATE passenger_debt_payments SET transaction_id=coalesce(transaction_id,?) WHERE id=?",
        snapshot.transactionId(),
        id);
    if (paid && !duplicate)
      notices.notify(
          owner,
          "Regularização atualizada",
          debts.summary(owner).purchasesBlocked()
              ? "O pagamento foi verificado. Ainda há um valor devido na carteira."
              : "Pagamento confirmado. A pendência financeira foi regularizada.",
          "wallet",
          id);
    receipts.processed(receipt);
    return new PaymentDto.Received(true, duplicate);
  }

  @Transactional
  public Optional<Claim> claim() {
    UUID lease = UUID.randomUUID();
    return sql
        .query(
            "WITH due AS (SELECT id FROM passenger_debt_payments WHERE state<>'REFUNDED' AND next_check_at<=now() AND (lease_until IS NULL OR lease_until<=now()) ORDER BY next_check_at LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE passenger_debt_payments p SET lease_id=?,lease_until=now()+interval '3 minutes' FROM due WHERE p.id=due.id RETURNING p.id",
            (r, n) -> new Claim(r.getObject("id", UUID.class), lease),
            lease)
        .stream()
        .findFirst();
  }

  @Transactional
  public void finish(Claim claim, String code) {
    sql.update(
        "UPDATE passenger_debt_payments SET lease_id=NULL,lease_until=NULL,last_error_code=?,next_check_at=now()+CASE WHEN ?::text IS NOT NULL THEN interval '2 minutes' WHEN state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW') THEN interval '1 minute' ELSE interval '6 hours' END WHERE id=? AND lease_id=?",
        code,
        code,
        claim.id(),
        claim.lease());
  }
}
