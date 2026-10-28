package com.unicarona.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.payment.config.*;
import com.unicarona.payment.dto.MercadoPagoDto;
import com.unicarona.payment.dto.MercadoPagoDto.GatewayDetail;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.provider.*;
import com.unicarona.payment.repository.MercadoPagoReadRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class MercadoPagoService {
  private static final Logger LOG = LoggerFactory.getLogger(MercadoPagoService.class);
  private final MercadoPagoOrdersClient client;
  private final MercadoPagoSettings settings;
  private final MercadoPagoSignature signature;
  private final JdbcTemplate sql;
  private final MercadoPagoTransactions transactions;
  private final WebhookReceipts receipts;
  private final TransactionRetries retries;
  private final ExternalCalls external;
  private final ObjectMapper json;
  private final DebtPaymentTransactions debtPayments;
  private final MercadoPagoReadRepository reads;

  public MercadoPagoService(
      MercadoPagoOrdersClient client,
      MercadoPagoSettings settings,
      JdbcTemplate sql,
      MercadoPagoTransactions transactions,
      WebhookReceipts receipts,
      TransactionRetries retries,
      ExternalCalls external,
      ObjectMapper json,
      DebtPaymentTransactions debtPayments,
      MercadoPagoReadRepository reads) {
    this.client = client;
    this.settings = settings;
    this.sql = sql;
    this.transactions = transactions;
    this.receipts = receipts;
    this.retries = retries;
    signature = new MercadoPagoSignature(settings.webhookSecret(), Clock.systemUTC());
    this.external = external;
    this.json = json;
    this.debtPayments = debtPayments;
    this.reads = reads;
  }

  public void card(Actor actor, UUID id, MercadoPagoDto.CardInput input, String key) {
    ApiException.require(
        !settings.publicKey().isBlank(),
        "PAYMENT_CONFIGURATION_ERROR",
        "Pagamento com cartão indisponível. Use Pix ou procure o suporte.",
        503);
    retries.call(() -> transactions.prepareCard(actor, id, input, key));
    submitCard(id);
  }

  public GatewayDetail sync(Actor actor, UUID id) {
    var rows =
        sql.queryForList(
            "SELECT provider_reference,method FROM payments WHERE id=? AND passenger_id=? AND provider='mercadopago'",
            id,
            actor.id());
    ApiException.require(!rows.isEmpty(), "PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404);
    String order = (String) rows.getFirst().get("provider_reference");
    if (order != null) {
      var snapshot =
          external.call(
              settings.timeoutMs(),
              "PAYMENT_PROVIDER_UNAVAILABLE",
              "Não foi possível atualizar o pagamento. Tente novamente.",
              () -> client.get(order));
      ApiException.require(
          snapshot.paymentId().equals(id) && snapshot.orderId().equals(order),
          "WEBHOOK_MISMATCH",
          "Pagamento externo incompatível.",
          422);
      apply(snapshot);
    } else if ("pix".equals(rows.getFirst().get("method"))) submitPix(id);
    else if (Boolean.TRUE.equals(
        sql.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM mercado_pago_card_commands WHERE payment_id=? AND status='pending')",
            Boolean.class,
            id))) submitCard(id);
    return detail(actor, id);
  }

  public void submitCard(UUID id) {
    try {
      var prepared = retries.call(() -> transactions.cardStarted(id));
      if (prepared.isEmpty()) return;
      var command = prepared.get();
      var snapshot =
          external.call(
              settings.timeoutMs(),
              "PAYMENT_PROVIDER_UNAVAILABLE",
              "O envio não foi confirmado. Atualize o status; a mesma cobrança será retomada.",
              () ->
                  client.create(
                      id,
                      command.cents(),
                      command.payerEmail(),
                      command.expiry(),
                      command.card().card()));
      ApiException.require(
          snapshot.paymentId().equals(id) && snapshot.amountCents() == command.cents(),
          "WEBHOOK_MISMATCH",
          "Pagamento externo incompatível.",
          422);
      apply(snapshot);
      LOG.info("Mercado Pago card order submitted payment={} state={}", id, snapshot.state());
    } catch (ApiException error) {
      retries.run(() -> transactions.cardFailed(id, error.code()));
      throw error;
    }
  }

  public PaymentProvider.Checkout create(PaymentProvider.Input input) throws Exception {
    retries.run(() -> transactions.preparePix(input.paymentId(), input.amountCents()));
    submitPix(input.paymentId());
    String order =
        sql.queryForObject(
            "SELECT provider_reference FROM payments WHERE id=?", String.class, input.paymentId());
    ApiException.require(order != null, "PAYMENT_STATE", "Cobrança indisponível ou expirada.", 409);
    return new PaymentProvider.Checkout(
        order,
        settings.origin() + "/dashboard.html?view=checkout&ref=" + input.paymentId(),
        input.expiresAt(),
        settings.test() ? "test" : "live");
  }

  public void submitPix(UUID id) {
    var rows = sql.queryForList("SELECT * FROM mercado_pago_pix_commands WHERE payment_id=?", id);
    ApiException.require(
        !rows.isEmpty(), "PAYMENT_NOT_FOUND", "Prepare o Pix antes de continuar.", 404);
    var row = rows.getFirst();
    if (!"pending".equals(row.get("status")) || !retries.call(() -> transactions.pixStarted(id)))
      return;
    try {
      var snapshot =
          external.call(
              settings.timeoutMs(),
              "PAYMENT_PROVIDER_UNAVAILABLE",
              "O envio do Pix não foi confirmado. Atualize o status; a mesma cobrança será retomada.",
              () ->
                  client.createPix(
                      id,
                      ((Number) row.get("amount_cents")).intValue(),
                      (String) row.get("payer_email"),
                      ((Number) row.get("expiration_seconds")).intValue()));
      ApiException.require(
          snapshot.paymentId().equals(id)
              && snapshot.amountCents() == ((Number) row.get("amount_cents")).intValue(),
          "WEBHOOK_MISMATCH",
          "Pagamento externo incompatível.",
          422);
      apply(snapshot);
      cancelIfNecessary(snapshot);
      LOG.info("event=mercadopago_pix_submitted payment={} state={}", id, snapshot.state());
    } catch (ApiException error) {
      retries.run(() -> transactions.pixFailed(id, error.code()));
      throw error;
    }
  }

  void cancelIfNecessary(MercadoPagoOrdersClient.Snapshot snapshot) {
    UUID id = snapshot.paymentId();
    String order = snapshot.orderId();
    var command = retries.call(() -> transactions.cancellation(id));
    if (command.isEmpty()) return;
    if ("action_required".equals(snapshot.status())) {
      try {
        external.call(
            settings.timeoutMs(),
            "PAYMENT_PROVIDER_UNAVAILABLE",
            "Não foi possível cancelar a cobrança no provedor.",
            () -> {
              client.cancel(order, command.get());
              return true;
            });
      } catch (ApiException error) {
        if (!"PAYMENT_PROVIDER_REJECTED".equals(error.code())) throw error;
      }
      // A successful HTTP response or cancellation race is never proof of cancellation.
      var fresh =
          external.call(
              settings.timeoutMs(),
              "PAYMENT_PROVIDER_UNAVAILABLE",
              "Não foi possível confirmar o cancelamento.",
              () -> client.get(order));
      ApiException.require(
          fresh.paymentId().equals(id) && fresh.orderId().equals(order),
          "WEBHOOK_MISMATCH",
          "Pagamento externo incompatível.",
          422);
      apply(fresh);
      snapshot = fresh;
    }
    if (Set.of(MercadoPagoStatus.CANCELLED, MercadoPagoStatus.EXPIRED, MercadoPagoStatus.REJECTED)
        .contains(snapshot.state())) retries.run(() -> transactions.cancelled(id, "cancelled"));
    else if (snapshot.state() == MercadoPagoStatus.APPROVED)
      retries.run(() -> transactions.cancelled(id, "paid_before_cancel"));
    LOG.info(
        "event=mercadopago_cancel_checked payment={} state={}",
        snapshot.paymentId(),
        snapshot.state());
  }

  public PaymentDto.Received webhook(
      String dataId, String requestId, String signed, String bodyId, String type) throws Exception {
    signature.verify(dataId, requestId, signed);
    ApiException.require(
        "order".equals(type) && dataId.equalsIgnoreCase(bodyId),
        "WEBHOOK_MISMATCH",
        "Notificação incompatível.",
        422);
    var snapshot =
        external.call(
            settings.timeoutMs(),
            "PAYMENT_PROVIDER_UNAVAILABLE",
            "Não foi possível verificar o pagamento no provedor.",
            () -> client.get(dataId.toUpperCase(Locale.ROOT)));
    ApiException.require(
        snapshot.orderId().equalsIgnoreCase(dataId),
        "WEBHOOK_MISMATCH",
        "Notificação incompatível.",
        422);
    LOG.info(
        "Mercado Pago webhook verified payment={} state={}",
        snapshot.paymentId(),
        snapshot.state());
    return apply(snapshot);
  }

  PaymentDto.Received apply(MercadoPagoOrdersClient.Snapshot snapshot) {
    // Fingerprint verified state, not the mutable notification envelope or any payer data.
    var payload = new LinkedHashMap<String, Object>();
    payload.put("order", snapshot.orderId());
    payload.put("payment", snapshot.paymentId());
    payload.put("amountCents", snapshot.amountCents());
    payload.put("state", snapshot.state());
    payload.put("detail", snapshot.statusDetail());
    payload.put("version", snapshot.version());
    payload.put("refunds", snapshot.refunds());
    payload.put("transactionStatus", snapshot.transactionStatus());
    payload.put("transactionDetail", snapshot.transactionDetail());
    payload.put("transaction", snapshot.transactionId());
    payload.put("providerStatus", snapshot.status());
    String event;
    try {
      event =
          "v2_"
              + snapshot.orderId()
              + "_"
              + HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(json.writeValueAsBytes(payload)));
    } catch (Exception error) {
      throw new IllegalStateException("Cannot fingerprint verified payment state.");
    }
    UUID receipt = retries.call(() -> receipts.record("mercadopago", event, payload));
    try {
      boolean debt =
          Boolean.TRUE.equals(
              sql.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM passenger_debt_payments WHERE id=?)",
                  Boolean.class,
                  snapshot.paymentId()));
      return retries.call(
          () ->
              debt ? debtPayments.apply(snapshot, receipt) : transactions.apply(snapshot, receipt));
    } catch (RuntimeException error) {
      receipts.failed(receipt, error);
      LOG.warn(
          "Mercado Pago application failed payment={} code={}",
          snapshot.paymentId(),
          error instanceof ApiException api ? api.code() : "PROCESSING_ERROR");
      throw error;
    }
  }

  public GatewayDetail detail(Actor actor, UUID paymentId) {
    return reads.detail(actor.id(), paymentId, settings.environment());
  }

  public MercadoPagoDto.RefundReview refundReview(Actor actor) {
    return reads.refundReview(actor.id());
  }

  public MercadoPagoDto.RefundDetail refunds(Actor actor, UUID transferId) {
    return reads.refunds(actor.id(), transferId);
  }
}
