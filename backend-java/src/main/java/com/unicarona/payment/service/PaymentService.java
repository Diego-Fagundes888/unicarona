package com.unicarona.payment.service;

import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.provider.*;
import com.unicarona.payment.repository.*;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final PaymentTransactions transactions;
  private final PaymentRepository payments;
  private final PaymentReadRepository reads;
  private final WebhookReceipts receipts;
  private final PaymentProvider provider;
  private final BillingSettings settings;
  private final WebhookAuthentication signing;
  private final TransactionRetries retries;
  private final ExternalCalls external;

  public PaymentService(
      PaymentTransactions transactions,
      PaymentRepository payments,
      PaymentReadRepository reads,
      WebhookReceipts receipts,
      PaymentProvider provider,
      BillingSettings settings,
      WebhookAuthentication signing,
      TransactionRetries retries,
      ExternalCalls external) {
    this.transactions = transactions;
    this.payments = payments;
    this.reads = reads;
    this.receipts = receipts;
    this.provider = provider;
    this.settings = settings;
    this.signing = signing;
    this.retries = retries;
    this.external = external;
  }

  public PaymentDto.Detail checkout(
      Actor actor, String id, PaymentDto.CheckoutInput input, String key) {
    var prepared = retries.call(() -> transactions.prepare(actor, id, input, key));
    UUID paymentId = UUID.fromString(id);
    if (!prepared.confirmed()) {
      var payment =
          payments
              .findById(paymentId)
              .orElseThrow(
                  () -> new ApiException("PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404));
      if (payment.getCheckoutUrl() == null
          && "pending".equals(payment.getStatus())
          && !("mercadopago".equals(provider.name()) && "card".equals(payment.getMethod()))) {
        var result =
            external.call(
                settings.timeoutMs(),
                "PAYMENT_PROVIDER_UNAVAILABLE",
                "Não foi possível conectar ao pagamento. Tente novamente; a mesma cobrança será reutilizada.",
                () ->
                    provider.createCheckout(
                        new PaymentProvider.Input(
                            paymentId,
                            payment.getExternalCents(),
                            payment.getMethod(),
                            ApiDates.timestamp(payment.getExpiresAt()),
                            id)));
        retries.run(() -> transactions.submitted(paymentId, result));
      }
    }
    return detail(actor, id);
  }

  @Transactional(readOnly = true)
  public PaymentDto.Detail detail(Actor actor, String id) {
    return reads.detail(UUID.fromString(id), actor.id());
  }

  @Transactional(readOnly = true)
  public PaymentDto.Summary summary(Actor actor, String id) {
    return reads.summary(UUID.fromString(id), actor.id());
  }

  public PaymentDto.Received receive(PaymentDto.Event event, String timestamp, String signature) {
    provider.authenticate(event, timestamp, signature);
    UUID receipt = retries.call(() -> receipts.record(provider.name(), event.eventId(), event));
    try {
      return retries.call(() -> transactions.receive(receipt, event, provider.name()));
    } catch (RuntimeException error) {
      receipts.failed(receipt, error);
      throw error;
    }
  }

  public PaymentDto.Received developmentResult(
      Actor actor, String id, PaymentDto.ResultInput input) {
    var payment = detail(actor, id);
    ApiException.require(
        payment.checkout() != null
            && "test".equals(payment.checkout().environment())
            && payment.externalCents() > 0,
        "TEST_PAYMENT_UNAVAILABLE",
        "Prepare uma cobrança de teste antes de continuar.",
        409);
    var event =
        new PaymentDto.Event(
            "local_" + id + "_" + input.status(),
            id,
            payment.checkout().reference(),
            payment.externalCents(),
            input.status());
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    return receive(event, timestamp, signing.signature("", timestamp, event));
  }
}
