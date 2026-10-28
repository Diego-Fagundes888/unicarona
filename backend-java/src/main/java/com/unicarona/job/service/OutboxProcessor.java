package com.unicarona.job.service;

import com.unicarona.job.repository.OutboxDeliveryRepository;
import com.unicarona.payment.service.DebtPaymentService;
import com.unicarona.payment.service.MercadoPagoService;
import com.unicarona.payment.service.TransferService;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.TransactionRetries;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Claims and completes in short transactions; delivery never holds a database lock. */
@Service
public class OutboxProcessor {
  private final OutboxDeliveryRepository jobs;
  private final TransferService transfers;
  private final TransactionRetries retries;
  private final ObjectProvider<MercadoPagoService> mercadoPago;
  private final ObjectProvider<DebtPaymentService> debtPayments;
  private final com.unicarona.notification.service.AuthEmailDelivery emailDelivery;

  public OutboxProcessor(
      OutboxDeliveryRepository jobs,
      TransferService transfers,
      TransactionRetries retries,
      ObjectProvider<MercadoPagoService> mercadoPago,
      ObjectProvider<DebtPaymentService> debtPayments,
      com.unicarona.notification.service.AuthEmailDelivery emailDelivery) {
    this.jobs = jobs;
    this.transfers = transfers;
    this.retries = retries;
    this.mercadoPago = mercadoPago;
    this.debtPayments = debtPayments;
    this.emailDelivery = emailDelivery;
  }

  @Transactional(propagation = Propagation.NEVER)
  public boolean processOne() {
    var claimed = retries.call(jobs::claim);
    if (claimed.isEmpty()) return false;
    var job = claimed.get();
    try {
      boolean emailSent = false;
      switch (job.type()) {
        case "email" -> emailSent = emailDelivery.deliver(job);
        case "transfer" ->
            transfers.submit(UUID.fromString(job.payload().path("transferId").asText()));
        case "mercadopago_card" ->
            mercadoPago
                .getObject()
                .submitCard(UUID.fromString(job.payload().path("paymentId").asText()));
        case "mercadopago_pix" ->
            mercadoPago
                .getObject()
                .submitPix(UUID.fromString(job.payload().path("paymentId").asText()));
        case "mercadopago_debt_pix" ->
            debtPayments
                .getObject()
                .submit(UUID.fromString(job.payload().path("paymentId").asText()));
        default -> throw new IllegalStateException("Unsupported job type.");
      }
      boolean delivered = emailSent;
      retries.run(() -> jobs.done(job, delivered));
    } catch (Exception error) {
      // Persist the old public error vocabulary, never exception messages or payloads.
      String code =
          error instanceof ApiException
              ? "AppError"
              : error instanceof java.util.concurrent.CancellationException
                  ? "AbortError"
                  : "Error";
      retries.run(() -> jobs.failed(job, code));
    }
    return true;
  }
}
