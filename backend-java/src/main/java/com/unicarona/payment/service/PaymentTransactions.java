package com.unicarona.payment.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.provider.PaymentProvider;
import com.unicarona.payment.repository.*;
import com.unicarona.reservation.repository.*;
import com.unicarona.reservation.service.RequestLocks;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactions {
  private final PaymentRepository payments;
  private final PaymentReadRepository reads;
  private final PaymentEligibilityRepository eligibility;
  private final RequestLocks locks;
  private final RequestRulesRepository rules;
  private final ActorsRepository actors;
  private final IdempotencyExecutor idempotency;
  private final LedgerService ledger;
  private final PaymentConfirmation confirmation;
  private final NotificationService notices;
  private final AuditRepository audit;
  private final WebhookReceiptRepository events;
  private final WebhookReceipts receipts;

  public PaymentTransactions(
      PaymentRepository payments,
      PaymentReadRepository reads,
      PaymentEligibilityRepository eligibility,
      RequestLocks locks,
      RequestRulesRepository rules,
      ActorsRepository actors,
      IdempotencyExecutor idempotency,
      LedgerService ledger,
      PaymentConfirmation confirmation,
      NotificationService notices,
      AuditRepository audit,
      WebhookReceiptRepository events,
      WebhookReceipts receipts) {
    this.payments = payments;
    this.reads = reads;
    this.eligibility = eligibility;
    this.locks = locks;
    this.rules = rules;
    this.actors = actors;
    this.idempotency = idempotency;
    this.ledger = ledger;
    this.confirmation = confirmation;
    this.notices = notices;
    this.audit = audit;
    this.events = events;
    this.receipts = receipts;
  }

  @Transactional
  public PaymentDto.Prepared prepare(
      Actor actor, String id, PaymentDto.CheckoutInput input, String key) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", id);
    body.put("method", input.method());
    body.put("useCredit", input.useCredit());
    return idempotency.execute(
        actor.id(),
        key,
        "payment:checkout",
        body,
        PaymentDto.Prepared.class,
        () -> {
          UUID paymentId = UUID.fromString(id), requestId = reads.ownRequest(paymentId, actor.id());
          var locked = locks.lock(requestId, actor.id(), RequestRulesRepository.Side.PASSENGER);
          var request = locked.request();
          var people = locked.persons();
          people.forEach(ActorsRepository::active);
          ActorsRepository.driver(
              people.stream()
                  .filter(p -> p.id().equals(request.getDriverId()))
                  .findFirst()
                  .orElseThrow());
          actors.notBlocked(request.getPassengerId(), request.getDriverId());
          var payment =
              payments
                  .lockById(paymentId)
                  .orElseThrow(
                      () ->
                          new ApiException("PAYMENT_NOT_FOUND", "Pagamento não encontrado.", 404));
          ApiException.require(
              "accepted".equals(request.getStatus())
                  && rules.valid(requestId)
                  && reads.valid(paymentId)
                  && Set.of("awaiting", "pending").contains(payment.getStatus()),
              "PAYMENT_STATE",
              "Cobrança indisponível ou expirada.",
              409);
          ApiException.require(
              eligibility.monthly(request, people),
              "MONTHLY_UNAVAILABLE",
              "O vínculo ou a elegibilidade mudou. Revise o mensal.",
              409);
          ApiException.require(
              eligibility.purchasesAllowed(actor.id()),
              "PASSENGER_DEBT",
              "Você tem um valor devido por estorno de crédito já utilizado. Regularize a pendência na carteira antes de comprar outra carona.",
              409);
          if ("pending".equals(payment.getStatus())) {
            ApiException.require(
                payment.getMethod().equals(input.method())
                    && payment.isCreditRequested() == input.useCredit(),
                "PAYMENT_STATE",
                "O checkout já está definido.",
                409);
            return new PaymentDto.Prepared(id, false);
          }
          var lines = rules.lockedLines(requestId);
          ApiException.require(
              rules.linesAvailable(
                  lines.stream().map(RequestRulesRepository.LockedLine::ride).toList(),
                  actor.id(),
                  request.getPreviousSubscriptionId()),
              "RIDE_FULL",
              "Uma das vagas já foi ocupada. Nenhuma cobrança foi iniciada.",
              409);
          reads.requireTripReview(paymentId, actor.id(), input.tripReview());
          int credit =
              input.useCredit()
                  ? Math.toIntExact(
                      Math.min(
                          ledger.balance(actor.id(), AccountKind.PASSENGER_CREDIT),
                          payment.getAmountCents()))
                  : 0;
          ApiException.require(
              credit == 0 || eligibility.creditSafe(actor.id()),
              "REFUND_REQUIRES_REVIEW",
              "Há uma movimentação financeira em análise. Procure o suporte antes de usar esse crédito.",
              409);
          payment.prepare(input.method(), input.useCredit(), credit);
          payments.flush();
          if (credit == payment.getAmountCents()) {
            confirmation.confirm(request, payment, lines);
            return new PaymentDto.Prepared(id, true);
          }
          audit.record(
              actor.id(),
              "checkout_prepared",
              "payment",
              paymentId,
              Map.of("externalCents", payment.getExternalCents(), "creditCents", credit));
          return new PaymentDto.Prepared(id, false);
        });
  }

  @Transactional
  public PaymentDto.Received receive(UUID receipt, PaymentDto.Event event, String provider) {
    var saved =
        events
            .lockById(receipt)
            .orElseThrow(() -> new IllegalStateException("Authenticated receipt disappeared."));
    if ("processed".equals(saved.getStatus())) return new PaymentDto.Received(true, true);
    UUID id = UUID.fromString(event.paymentId());
    var reference = reads.reference(id);
    var locked =
        locks.lock(
            reference.request(), reference.passenger(), RequestRulesRepository.Side.PASSENGER);
    var request = locked.request();
    var people = locked.persons();
    var payment =
        payments
            .lockById(id)
            .orElseThrow(
                () -> new ApiException("PAYMENT_NOT_FOUND", "Cobrança não encontrada.", 404));
    ApiException.require(
        payment.getProvider().equals(provider)
            && event.reference().equals(payment.getProviderReference())
            && payment.getExternalCents() == event.amountCents()
            && event.amountCents() > 0,
        "WEBHOOK_MISMATCH",
        "Evento não corresponde à cobrança.",
        422);
    if (payment.getPaidAt() != null) {
      receipts.processed(receipt);
      return new PaymentDto.Received(true, true);
    }
    if ("failed".equals(event.status())) {
      payment.fail();
      notices.notify(
          request.getPassengerId(),
          "Pagamento não aprovado",
          "A cobrança falhou. Você pode solicitar novamente.",
          "payments",
          id);
      if ("accepted".equals(request.getStatus())) request.cancel("payment_failed");
    } else {
      boolean monthly = eligibility.monthly(request, people);
      var lines = rules.lockedLines(request.getId());
      boolean eligible =
          people.stream().allMatch(p -> "active".equals(p.accountStatus()) && p.emailVerified())
              && people.stream()
                  .anyMatch(
                      p ->
                          p.id().equals(request.getDriverId())
                              && "approved".equals(p.driverStatus()))
              && monthly;
      boolean blocked = reads.blocked(request.getPassengerId(), request.getDriverId());
      boolean sufficient =
          ledger.balance(request.getPassengerId(), AccountKind.PASSENGER_CREDIT)
                  >= payment.getCreditCents()
              && (payment.getCreditCents() == 0
                  || eligibility.creditSafe(request.getPassengerId()));
      if (eligible
          && !blocked
          && "accepted".equals(request.getStatus())
          && rules.valid(request.getId())
          && reads.valid(id)
          && sufficient
          && rules.linesAvailable(
              lines.stream().map(RequestRulesRepository.LockedLine::ride).toList(),
              request.getPassengerId(),
              request.getPreviousSubscriptionId())) confirmation.confirm(request, payment, lines);
      else {
        ledger.post(
            LedgerCommand.receiptCredit(
                id,
                "Vaga indisponível após confirmação do provedor",
                List.of(
                    new Posting(null, AccountKind.PROVIDER_CLEARING, -payment.getExternalCents()),
                    new Posting(
                        request.getPassengerId(),
                        AccountKind.PASSENGER_CREDIT,
                        payment.getExternalCents()))));
        payment.approve(reads.now(), true);
        if ("accepted".equals(request.getStatus())) request.cancel("paid_without_availability");
        notices.notify(
            request.getPassengerId(),
            "Valor devolvido em crédito",
            "A vaga ficou indisponível. O valor recebido voltou integralmente para seu crédito; você pode solicitar devolução.",
            "wallet",
            id);
      }
    }
    payments.flush();
    audit.record(
        null,
        "payment_event_processed",
        "payment",
        id,
        Map.of("eventId", event.eventId(), "status", event.status()));
    receipts.processed(receipt);
    return new PaymentDto.Received(true, false);
  }

  @Transactional
  public void submitted(UUID id, PaymentProvider.Checkout result) {
    payments.submitted(id, result.reference(), result.url(), result.environment());
  }
}
