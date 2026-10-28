package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.config.MercadoPagoSettings;
import com.unicarona.payment.dto.MercadoPagoDto;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.provider.MercadoPagoOrdersClient.Snapshot;
import com.unicarona.payment.provider.MercadoPagoStatus;
import com.unicarona.security.Actor;
import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.IdempotencyExecutor;
import com.unicarona.shared.repository.ActorsRepository;
import com.unicarona.shared.repository.OutboxRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Attach the provider identity and apply the existing ledger transaction atomically. */
public class MercadoPagoTransactions {
  public record ReconcileClaim(UUID payment, String order, UUID lease) {}

  public record CardSubmission(
      int cents, java.time.Instant expiry, String payerEmail, MercadoPagoDto.CardInput card) {
    @Override
    public String toString() {
      return "CardSubmission[REDACTED]";
    }
  }

  private final JdbcTemplate sql;
  private final PaymentTransactions payments;
  private final WebhookReceipts receipts;
  private final MercadoPagoSettings settings;
  private final AuthSettings auth;
  private final CryptoService crypto;
  private final ObjectMapper json;
  private final IdempotencyExecutor idempotency;
  private final OutboxRepository jobs;
  private final MercadoPagoRefundTransactions refunds;
  private final ActorsRepository actors;

  public MercadoPagoTransactions(
      JdbcTemplate sql,
      PaymentTransactions payments,
      WebhookReceipts receipts,
      MercadoPagoSettings settings,
      AuthSettings auth,
      CryptoService crypto,
      ObjectMapper json,
      IdempotencyExecutor idempotency,
      OutboxRepository jobs,
      MercadoPagoRefundTransactions refunds,
      ActorsRepository actors) {
    this.sql = sql;
    this.payments = payments;
    this.receipts = receipts;
    this.settings = settings;
    this.auth = auth;
    this.crypto = crypto;
    this.json = json;
    this.idempotency = idempotency;
    this.jobs = jobs;
    this.refunds = refunds;
    this.actors = actors;
  }

  @Transactional
  public void preparePix(UUID id, int cents) {
    UUID owner = sql.queryForObject("SELECT passenger_id FROM payments WHERE id=?", UUID.class, id);
    actors.lockUser(owner);
    requirePurchasesAllowed(owner);
    var rows =
        sql.queryForList(
            "SELECT p.*,u.email FROM payments p JOIN users u ON u.id=p.passenger_id WHERE p.id=? FOR UPDATE OF p",
            id);
    ApiException.require(!rows.isEmpty(), "PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404);
    var p = rows.getFirst();
    ApiException.require(
        "mercadopago".equals(p.get("provider"))
            && "pix".equals(p.get("method"))
            && cents == ((Number) p.get("external_cents")).intValue()
            && cents > 0,
        "PAYMENT_STATE",
        "Pagamento incompatível.",
        409);
    if (Boolean.TRUE.equals(
        sql.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM mercado_pago_pix_commands WHERE payment_id=?)",
            Boolean.class,
            id))) return;
    ApiException.require(
        "pending".equals(p.get("status"))
            && p.get("provider_reference") == null
            && Boolean.TRUE.equals(
                sql.queryForObject(
                    "SELECT expires_at>now() FROM payments WHERE id=?", Boolean.class, id)),
        "PAYMENT_STATE",
        "Cobrança indisponível ou expirada.",
        409);
    sql.update(
        "INSERT INTO mercado_pago_pix_commands(payment_id,amount_cents,payer_email,expiration_seconds) SELECT id,external_cents,?,greatest(1800,least(2592000,floor(extract(epoch FROM expires_at-now()))::integer)) FROM payments WHERE id=?",
        p.get("email"),
        id);
    jobs.enqueue("mercadopago_pix", "mercadopago_pix:" + id, Map.of("paymentId", id));
  }

  @Transactional
  public boolean pixStarted(UUID id) {
    UUID owner = sql.queryForObject("SELECT passenger_id FROM payments WHERE id=?", UUID.class, id);
    actors.lockUser(owner);
    var rows =
        sql.queryForList(
            "SELECT c.started_at,c.attempts,p.expires_at>now() AND q.expires_at>now() AND q.status='accepted' AS valid,p.status FROM mercado_pago_pix_commands c JOIN payments p ON p.id=c.payment_id JOIN ride_requests q ON q.id=p.request_id WHERE c.payment_id=? AND c.status='pending' FOR UPDATE OF c",
            id);
    if (rows.isEmpty()) return false;
    var p = rows.getFirst();
    ApiException.require(
        p.get("started_at") != null || ((Number) p.get("attempts")).intValue() == 0,
        "PAYMENT_COMMAND_SUBMISSION_UNKNOWN",
        "O envio anterior precisa ser conferido pelo suporte. Não faça outro pagamento.",
        503);
    if (p.get("started_at") == null) requirePurchasesAllowed(owner);
    if (p.get("started_at") == null
        && (!Boolean.TRUE.equals(p.get("valid")) || !"pending".equals(p.get("status")))) {
      sql.update(
          "UPDATE mercado_pago_pix_commands SET status='failed',last_error_code='PAYMENT_EXPIRED',updated_at=now() WHERE payment_id=?",
          id);
      return false;
    }
    sql.update(
        "UPDATE mercado_pago_pix_commands SET started_at=coalesce(started_at,now()),attempts=attempts+1,updated_at=now() WHERE payment_id=?",
        id);
    return true;
  }

  @Transactional
  public void pixFailed(UUID id, String code) {
    sql.update(
        "UPDATE mercado_pago_pix_commands SET status=CASE WHEN ?='PAYMENT_PROVIDER_REJECTED' AND attempts=1 THEN 'failed' ELSE status END,last_error_code=?,updated_at=now() WHERE payment_id=? AND status='pending'",
        code,
        code,
        id);
  }

  @Transactional
  public Optional<UUID> cancellation(UUID id) {
    var rows =
        sql.queryForList(
            "SELECT m.cancel_command_id,m.cancel_completed_at FROM mercado_pago_orders m JOIN payments p ON p.id=m.payment_id JOIN ride_requests q ON q.id=p.request_id WHERE m.payment_id=? AND (m.cancel_command_id IS NOT NULL OR (p.paid_at IS NULL AND (p.status IN ('expired','failed') OR q.status IN ('cancelled','expired') OR p.expires_at<=now() OR q.expires_at<=now()))) FOR UPDATE OF m",
            id);
    if (rows.isEmpty() || rows.getFirst().get("cancel_completed_at") != null)
      return Optional.empty();
    var existing = (UUID) rows.getFirst().get("cancel_command_id");
    if (existing != null) return Optional.of(existing);
    UUID command = UUID.randomUUID();
    sql.update(
        "UPDATE mercado_pago_orders SET cancel_command_id=?,cancel_requested_at=now() WHERE payment_id=?",
        command,
        id);
    return Optional.of(command);
  }

  @Transactional
  public void cancelled(UUID id, String outcome) {
    sql.update(
        "UPDATE mercado_pago_orders SET cancel_completed_at=now(),cancel_outcome=? WHERE payment_id=? AND cancel_command_id IS NOT NULL AND cancel_completed_at IS NULL",
        outcome,
        id);
  }

  @Transactional
  public UUID prepareCard(Actor actor, UUID id, MercadoPagoDto.CardInput input, String key) {
    return idempotency.executeUnorderedObject(
        actor.id(),
        key,
        "payment:card",
        Map.of("paymentId", id, "card", input),
        UUID.class,
        () -> {
          actors.lockUser(actor.id());
          var rows =
              sql.queryForList(
                  "SELECT * FROM payments WHERE id=? AND passenger_id=? FOR UPDATE",
                  id,
                  actor.id());
          ApiException.require(
              !rows.isEmpty(), "PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404);
          var p = rows.getFirst();
          requirePurchasesAllowed(actor.id());
          String encoded;
          try {
            encoded = json.writeValueAsString(input);
          } catch (Exception error) {
            throw new IllegalStateException("Cannot encode card command.");
          }
          String hash = crypto.hmac(encoded, auth.pepper());
          var existing =
              sql.queryForList(
                  "SELECT body_hash FROM mercado_pago_card_commands WHERE payment_id=?", id);
          if (!existing.isEmpty()) {
            ApiException.require(
                hash.equals(existing.getFirst().get("body_hash")),
                "CARD_ALREADY_SUBMITTED",
                "Este pagamento já foi enviado. Atualize o status para acompanhar a mesma cobrança.",
                409);
            return id;
          }
          ApiException.require(
              "mercadopago".equals(p.get("provider"))
                  && "card".equals(p.get("method"))
                  && "pending".equals(p.get("status"))
                  && p.get("provider_reference") == null
                  && ((Number) p.get("external_cents")).intValue() > 0
                  && Boolean.TRUE.equals(
                      sql.queryForObject(
                          "SELECT expires_at>now() FROM payments WHERE id=?", Boolean.class, id)),
              "PAYMENT_STATE",
              "Prepare um pagamento com cartão válido antes de continuar.",
              409);
          String email =
              sql.queryForObject("SELECT email FROM users WHERE id=?", String.class, actor.id());
          String ciphertext =
              new MercadoPagoCardCommand(json, crypto, auth)
                  .freeze(
                      id, actor.id(), ((Number) p.get("external_cents")).intValue(), email, input);
          sql.update(
              "INSERT INTO mercado_pago_card_commands(payment_id,body_hash,payload_ciphertext) VALUES(?,?,?)",
              id,
              hash,
              ciphertext);
          jobs.enqueue("mercadopago_card", "mercadopago_card:" + id, Map.of("paymentId", id));
          return id;
        });
  }

  @Transactional
  public Optional<CardSubmission> cardStarted(UUID id) {
    UUID owner = sql.queryForObject("SELECT passenger_id FROM payments WHERE id=?", UUID.class, id);
    actors.lockUser(owner);
    var rows =
        sql.queryForList(
            "SELECT c.started_at,c.attempts,c.payload_ciphertext,c.body_hash,p.external_cents,p.expires_at,u.email,p.provider_reference,p.expires_at>now() AND q.expires_at>now() AND q.status='accepted' AS valid,p.status FROM mercado_pago_card_commands c JOIN payments p ON p.id=c.payment_id JOIN users u ON u.id=p.passenger_id JOIN ride_requests q ON q.id=p.request_id WHERE c.payment_id=? AND c.status='pending' FOR UPDATE OF c,p",
            id);
    if (rows.isEmpty()) return Optional.empty();
    var p = rows.getFirst();
    if (p.get("provider_reference") != null) {
      sql.update(
          "UPDATE mercado_pago_card_commands SET status='done',payload_ciphertext=NULL,last_error_code=NULL,updated_at=now() WHERE payment_id=?",
          id);
      return Optional.empty();
    }
    ApiException.require(
        p.get("started_at") != null || ((Number) p.get("attempts")).intValue() == 0,
        "PAYMENT_COMMAND_SUBMISSION_UNKNOWN",
        "O envio anterior precisa ser conferido pelo suporte. Não faça outro pagamento.",
        503);
    if (p.get("started_at") == null) requirePurchasesAllowed(owner);
    // Unknown prior submissions must retain their identity for recovery; only never-sent commands
    // can stop here.
    if (p.get("started_at") == null
        && (!Boolean.TRUE.equals(p.get("valid")) || !"pending".equals(p.get("status")))) {
      sql.update(
          "UPDATE mercado_pago_card_commands SET status='failed',payload_ciphertext=NULL,last_error_code='PAYMENT_EXPIRED',updated_at=now() WHERE payment_id=?",
          id);
      return Optional.empty();
    }
    int cents = ((Number) p.get("external_cents")).intValue();
    var codec = new MercadoPagoCardCommand(json, crypto, auth);
    var command =
        codec.read(
            (String) p.get("payload_ciphertext"), (String) p.get("body_hash"), id, owner, cents);
    // Historical commands never stored the payer contact. Once sent, its value
    // cannot be reconstructed after a profile correction. A verified webhook
    // can still attach the original order and clear the recovery input.
    ApiException.require(
        !command.legacy() || p.get("started_at") == null,
        "PAYMENT_COMMAND_CONTACT_UNKNOWN",
        "O envio anterior precisa ser conferido pelo suporte. Não faça outro pagamento.",
        503);
    String email = command.legacy() ? (String) p.get("email") : command.payerEmail();
    if (command.legacy())
      sql.update(
          "UPDATE mercado_pago_card_commands SET payload_ciphertext=? WHERE payment_id=?",
          codec.freeze(id, owner, cents, email, command.card()),
          id);
    sql.update(
        "UPDATE mercado_pago_card_commands SET started_at=coalesce(started_at,now()),attempts=attempts+1,updated_at=now() WHERE payment_id=?",
        id);
    return Optional.of(
        new CardSubmission(
            cents, ((java.sql.Timestamp) p.get("expires_at")).toInstant(), email, command.card()));
  }

  @Transactional
  public void cardFailed(UUID id, String code) {
    if ("PAYMENT_PROVIDER_REJECTED".equals(code))
      sql.update(
          "UPDATE mercado_pago_card_commands SET status=CASE WHEN attempts=1 THEN 'failed' ELSE status END,payload_ciphertext=CASE WHEN attempts=1 THEN NULL ELSE payload_ciphertext END,last_error_code=?,updated_at=now() WHERE payment_id=? AND status='pending'",
          code,
          id);
    else
      sql.update(
          "UPDATE mercado_pago_card_commands SET last_error_code=?,updated_at=now() WHERE payment_id=? AND status='pending'",
          code,
          id);
  }

  @Transactional
  public Optional<ReconcileClaim> claimReconciliation() {
    UUID lease = UUID.randomUUID();
    var claims =
        sql.query(
            """
            WITH due AS (
                SELECT payment_id FROM mercado_pago_orders
                WHERE (state<>'REFUNDED' OR EXISTS(SELECT 1 FROM mercado_pago_refunds r WHERE r.payment_id=mercado_pago_orders.payment_id AND r.transfer_id IS NOT NULL AND r.status IN ('pending','processing'))) AND next_check_at<=now()
                  AND (reconcile_lease_until IS NULL OR reconcile_lease_until<=now())
                ORDER BY next_check_at,payment_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE mercado_pago_orders m SET reconcile_lease=?,
                reconcile_lease_until=now()+(greatest(60,5*(? / 1000)+30)*interval '1 second')
            FROM due WHERE m.payment_id=due.payment_id
            RETURNING m.payment_id,m.order_id
            """,
            (row, n) ->
                new ReconcileClaim(
                    row.getObject("payment_id", UUID.class), row.getString("order_id"), lease),
            lease,
            settings.timeoutMs());
    return claims.stream().findFirst();
  }

  @Transactional
  public void finishReconciliation(ReconcileClaim claim, String code) {
    if (code == null)
      sql.update(
          """
            UPDATE mercado_pago_orders SET reconcile_lease=NULL,reconcile_lease_until=NULL,
              reconcile_failures=0,reconcile_error_code=NULL,
              next_check_at=now()+CASE WHEN state IN ('CREATED','PENDING','PROCESSING','UNDER_REVIEW')
                THEN interval '1 minute' ELSE interval '6 hours' END
            WHERE payment_id=? AND reconcile_lease=?
            """,
          claim.payment(),
          claim.lease());
    else
      sql.update(
          """
            UPDATE mercado_pago_orders SET reconcile_lease=NULL,reconcile_lease_until=NULL,
              reconcile_failures=reconcile_failures+1,reconcile_error_code=?,
              next_check_at=now()+(least(3600,60*power(2,least(reconcile_failures,6)))*interval '1 second')
            WHERE payment_id=? AND reconcile_lease=?
            """,
          code,
          claim.payment(),
          claim.lease());
  }

  @Transactional
  public PaymentDto.Received apply(Snapshot snapshot, UUID receipt) {
    var identity =
        sql.queryForList(
            "SELECT p.id,p.external_cents,p.provider,p.provider_reference,p.method,p.passenger_id,q.driver_id FROM payments p JOIN ride_requests q ON q.id=p.request_id WHERE p.id=?",
            snapshot.paymentId());
    ApiException.require(
        !identity.isEmpty(), "PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404);
    var payment = identity.getFirst();
    actors.lockUsers(List.of((UUID) payment.get("passenger_id"), (UUID) payment.get("driver_id")));
    ApiException.require(
        "mercadopago".equals(payment.get("provider"))
            && ((Number) payment.get("external_cents")).intValue() == snapshot.amountCents(),
        "WEBHOOK_MISMATCH",
        "Pagamento externo não corresponde à cobrança.",
        422);
    String url = settings.origin() + "/dashboard.html?view=checkout&ref=" + snapshot.paymentId();
    sql.update(
        "UPDATE payments SET provider_reference=?,checkout_url=?,gateway_environment=? WHERE id=? AND provider_reference IS NULL",
        snapshot.orderId(),
        url,
        settings.test() ? "test" : "live",
        snapshot.paymentId());
    String attached =
        sql.queryForObject(
            "SELECT provider_reference FROM payments WHERE id=?",
            String.class,
            snapshot.paymentId());
    ApiException.require(
        snapshot.orderId().equals(attached),
        "WEBHOOK_MISMATCH",
        "Identidade de pagamento incompatível.",
        422);
    sql.update(
        "UPDATE mercado_pago_card_commands SET status='done',payload_ciphertext=NULL,last_error_code=NULL,updated_at=now() WHERE payment_id=? AND status='pending'",
        snapshot.paymentId());
    sql.update(
        "UPDATE mercado_pago_pix_commands SET status='done',last_error_code=NULL,updated_at=now() WHERE payment_id=? AND status='pending'",
        snapshot.paymentId());
    sql.update(
        "INSERT INTO mercado_pago_orders(payment_id,order_id,state,provider_status,status_detail,provider_version) VALUES(?,?,?,?,?,?) ON CONFLICT(payment_id) DO NOTHING",
        snapshot.paymentId(),
        snapshot.orderId(),
        snapshot.state().name(),
        snapshot.status(),
        snapshot.statusDetail(),
        snapshot.version());
    var existing =
        sql.queryForMap(
            "SELECT * FROM mercado_pago_orders WHERE payment_id=? FOR UPDATE",
            snapshot.paymentId());
    ApiException.require(
        snapshot.orderId().equals(existing.get("order_id")),
        "WEBHOOK_MISMATCH",
        "Identidade de pagamento incompatível.",
        422);
    if (snapshot.version() > 0
        && snapshot.version() < ((Number) existing.get("provider_version")).longValue()) {
      receipts.processed(receipt);
      return new PaymentDto.Received(true, true);
    }
    ApiException.require(
        existing.get("transaction_id") == null
            || snapshot.transactionId() == null
            || snapshot.transactionId().equals(existing.get("transaction_id")),
        "WEBHOOK_MISMATCH",
        "Transação de pagamento incompatível.",
        422);
    sql.update(
        "UPDATE mercado_pago_orders SET review_receipt_id=?,review_checked_at=now(),provider_version=greatest(provider_version,?) WHERE payment_id=?",
        receipt,
        snapshot.version(),
        snapshot.paymentId());
    // Even a previously refunded lifecycle can receive a dispute; keep its receipt immutable.
    refunds.observeChargeback(snapshot);
    // A fresh GET may not contain a version. Never downgrade a paid lifecycle to pending.
    boolean received =
        Set.of("APPROVED", "REFUNDED", "PARTIALLY_REFUNDED").contains(existing.get("state"));
    if (received
        && Set.of(
                MercadoPagoStatus.CREATED,
                MercadoPagoStatus.PENDING,
                MercadoPagoStatus.PROCESSING,
                MercadoPagoStatus.REJECTED,
                MercadoPagoStatus.CANCELLED,
                MercadoPagoStatus.EXPIRED)
            .contains(snapshot.state())) {
      receipts.processed(receipt);
      return new PaymentDto.Received(true, true);
    }
    if (("REFUNDED".equals(existing.get("state")) && snapshot.state() != MercadoPagoStatus.REFUNDED)
        || ("PARTIALLY_REFUNDED".equals(existing.get("state"))
            && snapshot.state() == MercadoPagoStatus.APPROVED)) {
      receipts.processed(receipt);
      return new PaymentDto.Received(true, true);
    }
    sql.update(
        "UPDATE mercado_pago_orders SET state=?,provider_status=?,status_detail=?,provider_version=greatest(provider_version,?),qr_code=coalesce(?,qr_code),qr_base64=coalesce(?,qr_base64),ticket_url=coalesce(?,ticket_url),challenge_url=?,checked_at=now(),updated_at=now() WHERE payment_id=?",
        snapshot.state().name(),
        snapshot.status(),
        snapshot.statusDetail(),
        snapshot.version(),
        snapshot.qrCode(),
        snapshot.qrBase64(),
        snapshot.ticketUrl(),
        snapshot.challengeUrl(),
        snapshot.paymentId());
    sql.update(
        "UPDATE mercado_pago_orders SET transaction_id=coalesce(transaction_id,?) WHERE payment_id=?",
        snapshot.transactionId(),
        snapshot.paymentId());
    refunds.observe(snapshot);
    if (snapshot.state() == MercadoPagoStatus.APPROVED
        || Set.of(
                MercadoPagoStatus.REJECTED, MercadoPagoStatus.CANCELLED, MercadoPagoStatus.EXPIRED)
            .contains(snapshot.state())) {
      String status = snapshot.state() == MercadoPagoStatus.APPROVED ? "approved" : "failed";
      return payments.receive(
          receipt,
          new PaymentDto.Event(
              "mp_" + receipt,
              snapshot.paymentId().toString(),
              snapshot.orderId(),
              snapshot.amountCents(),
              status),
          "mercadopago");
    }
    receipts.processed(receipt);
    return new PaymentDto.Received(true, false);
  }

  private void requirePurchasesAllowed(UUID owner) {
    ApiException.require(
        sql.queryForObject(
                "SELECT coalesce(sum(e.cents),0)::bigint FROM ledger_accounts a JOIN ledger_entries e ON e.account_id=a.id WHERE a.owner_id=? AND a.kind='passenger_receivable'",
                Long.class,
                owner)
            >= 0,
        "PASSENGER_DEBT",
        "Regularize o valor devido na carteira antes de comprar outra carona.",
        409);
  }
}
