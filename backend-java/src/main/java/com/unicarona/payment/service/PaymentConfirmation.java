package com.unicarona.payment.service;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.entity.*;
import com.unicarona.payment.ledger.*;
import com.unicarona.payment.ledger.LedgerCommand.Posting;
import com.unicarona.payment.repository.*;
import com.unicarona.reservation.entity.*;
import com.unicarona.reservation.repository.*;
import com.unicarona.security.CryptoService;
import com.unicarona.subscription.entity.*;
import com.unicarona.subscription.repository.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PaymentConfirmation {
  private final LedgerService ledger;
  private final SubscriptionRepository subscriptions;
  private final SubscriptionScheduleRepository schedule;
  private final ReservationRepository reservations;
  private final ParcelRepository parcels;
  private final PaymentRepository payments;
  private final RideRequestRepository requests;
  private final PaymentReadRepository reads;
  private final CryptoService crypto;
  private final AuthSettings auth;
  private final NotificationService notices;
  private final SecureRandom random = new SecureRandom();

  public PaymentConfirmation(
      LedgerService ledger,
      SubscriptionRepository subscriptions,
      SubscriptionScheduleRepository schedule,
      ReservationRepository reservations,
      ParcelRepository parcels,
      PaymentRepository payments,
      RideRequestRepository requests,
      PaymentReadRepository reads,
      CryptoService crypto,
      AuthSettings auth,
      NotificationService notices) {
    this.ledger = ledger;
    this.subscriptions = subscriptions;
    this.schedule = schedule;
    this.reservations = reservations;
    this.parcels = parcels;
    this.payments = payments;
    this.requests = requests;
    this.reads = reads;
    this.crypto = crypto;
    this.auth = auth;
    this.notices = notices;
  }

  /** Participants, request, payment and ordered ride rows remain locked through commit. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void confirm(
      RideRequest request, Payment payment, List<RequestRulesRepository.LockedLine> lines) {
    UUID subscription = null;
    if ("mensal".equals(request.getType())) {
      var p = request.getPreview();
      var days = new ArrayList<Integer>();
      p.path("weekdays").forEach(day -> days.add(day.asInt()));
      subscription =
          subscriptions
              .saveAndFlush(
                  new Subscription(
                      request.getRoutineId(),
                      request.getDriverId(),
                      request.getPassengerId(),
                      UUID.fromString(p.path("classId").asText()),
                      p.path("startDate").asText(),
                      p.path("endDate").asText(),
                      days,
                      p.path("directions").asText(),
                      payment.getAmountCents(),
                      Math.toIntExact(Math.round(p.path("discount").asDouble() * 10000)),
                      request.getPreviousSubscriptionId()))
              .getId();
      if (request.getPreviousSubscriptionId() != null) {
        schedule.releaseHolds(request.getPreviousSubscriptionId());
        subscriptions.renewed(request.getPreviousSubscriptionId());
      }
    }
    var entries = new ArrayList<Posting>();
    entries.add(
        new Posting(request.getPassengerId(), AccountKind.FUTURE, payment.getAmountCents()));
    if (payment.getExternalCents() != 0)
      entries.add(new Posting(null, AccountKind.PROVIDER_CLEARING, -payment.getExternalCents()));
    if (payment.getCreditCents() != 0)
      entries.add(
          new Posting(
              request.getPassengerId(), AccountKind.PASSENGER_CREDIT, -payment.getCreditCents()));
    ledger.post(
        LedgerCommand.receipt(payment.getId(), "Pagamento confirmado; parcelas futuras", entries));
    for (var line : lines) {
      String pin = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
      var reservation =
          reservations.saveAndFlush(
              new Reservation(
                  request.getId(),
                  line.ride().id(),
                  request.getPassengerId(),
                  request.getDriverId(),
                  subscription,
                  payment.getId(),
                  crypto.encrypt(pin, auth.encryptionKey()),
                  crypto.hmac(pin, auth.pepper()),
                  line.cents()));
      parcels.saveAndFlush(
          new Parcel(
              reservation.getId(),
              payment.getId(),
              request.getPassengerId(),
              request.getDriverId(),
              line.cents(),
              line.commissionBps()));
      if (subscription != null)
        schedule.saveAndFlush(
            new SubscriptionSchedule(
                subscription, line.ride().id(), reservation.getId(), line.cents()));
    }
    payment.approve(reads.now(), false);
    request.confirm();
    payments.flush();
    requests.flush();
    notices.notify(
        request.getPassengerId(),
        "Reserva confirmada",
        "Confira os trechos e apresente o PIN apenas no embarque.",
        "reservations",
        request.getId());
    notices.notify(
        request.getDriverId(),
        "Passageiro confirmado",
        "Pagamento confirmado e vaga reservada.",
        "reservations",
        request.getId());
  }
}
