package com.unicarona.reservation.service;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.occurrence.service.OccurrenceRecorder;
import com.unicarona.payment.entity.Parcel;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.reservation.dto.LifecycleDto;
import com.unicarona.reservation.repository.LifecycleRepository;
import com.unicarona.reservation.repository.LifecycleRepository.Reservation;
import com.unicarona.security.*;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.AuditRepository;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Domain decisions and their financial effects commit together, with users locked before rides. */
@Service
@Transactional
public class LifecycleTransactions {
  private static final Set<String> ACTIVE = Set.of("confirmed", "boarded", "awaiting_confirmation");
  private final RideLifecycleLocks locks;
  private final LifecycleRepository writes;
  private final ParcelService financial;
  private final ParcelRepository parcels;
  private final OccurrenceRecorder cases;
  private final NotificationService notices;
  private final AuditRepository audit;
  private final IdempotencyExecutor idempotency;
  private final CryptoService crypto;
  private final AuthSettings settings;

  public LifecycleTransactions(
      RideLifecycleLocks locks,
      LifecycleRepository writes,
      ParcelService financial,
      ParcelRepository parcels,
      OccurrenceRecorder cases,
      NotificationService notices,
      AuditRepository audit,
      IdempotencyExecutor idempotency,
      CryptoService crypto,
      AuthSettings settings) {
    this.locks = locks;
    this.writes = writes;
    this.financial = financial;
    this.parcels = parcels;
    this.cases = cases;
    this.notices = notices;
    this.audit = audit;
    this.idempotency = idempotency;
    this.crypto = crypto;
    this.settings = settings;
  }

  private <T> T once(
      Actor actor,
      String id,
      String key,
      String scope,
      Class<T> reply,
      Supplier<T> work,
      Object... fields) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", id);
    for (int index = 0; index < fields.length; index += 2)
      body.put((String) fields[index], fields[index + 1]);
    return idempotency.execute(
        actor.id(),
        key,
        scope,
        body,
        reply,
        () -> {
          T result = work.get();
          parcels.flush();
          return result;
        });
  }

  private static UUID uuid(String id) {
    return UUID.fromString(id);
  }

  private static LifecycleDto.Status status(String id, String value) {
    return new LifecycleDto.Status(id, value);
  }

  private void audit(Actor actor, String action, String type, UUID id) {
    audit.record(actor.id(), action, type, id, Map.of());
  }

  private void planned(String state) {
    ApiException.require(
        "planned".equals(state), "RIDE_STATE", "O trecho já começou ou foi encerrado.", 409);
  }

  private void awaitFinal(Reservation reservation, Parcel parcel, String availability) {
    if (!"future".equals(parcel.getStatus())) return;
    writes.awaitFinal(reservation.id(), parcel.getId(), availability);
    notices.notify(
        reservation.passenger(),
        "no_class".equals(availability) ? "Sem aula informado" : "Confirme o trecho",
        "Você tem 24 horas para confirmar ou contestar esta parcela.",
        "reservation",
        reservation.id());
  }

  public LifecycleDto.Status start(Actor actor, String id, String key) {
    return once(
        actor,
        id,
        key,
        "ride:start",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.ride(uuid(id), actor.id(), true, false);
          var ride = data.ride();
          ApiException.require(
              ride.today() && "planned".equals(ride.status()),
              "RIDE_STATE",
              "Inicie um trecho previsto para hoje.",
              409);
          writes.start(ride.id());
          audit(actor, "ride_started", "ride", ride.id());
          return status(id, "active");
        });
  }

  public LifecycleDto.Status board(Actor actor, String id, String pin, String key) {
    return once(
        actor,
        id,
        key,
        "reservation:board",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.reservation(uuid(id), actor.id(), true);
          var ride = data.data().ride();
          var reservation = data.reservation();
          ApiException.require(
              ride.today()
                  && Set.of("planned", "active").contains(ride.status())
                  && "confirmed".equals(reservation.status())
                  && writes.boardingPinAvailable(reservation.id()),
              "BOARDING_UNAVAILABLE",
              "Embarque indisponível neste trecho.",
              409);
          ApiException.require(
              crypto.equalToken(crypto.hmac(pin, settings.pepper()), reservation.pinHash()),
              "PIN_INVALID",
              "Código incorreto. Confira o PIN deste passageiro e trecho.",
              422);
          writes.board(reservation.id());
          audit(actor, "boarding_registered", "reservation", reservation.id());
          return status(id, "boarded");
        },
        "pinHash",
        crypto.hmac(pin, settings.pepper()));
  }

  public LifecycleDto.Status finish(Actor actor, String id, boolean availability, String key) {
    return once(
        actor,
        id,
        key,
        "ride:finish",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.ride(uuid(id), actor.id(), true, true);
          var ride = data.ride();
          ApiException.require(
              "active".equals(ride.status()),
              "RIDE_STATE",
              "Inicie o trecho antes de concluir.",
              409);
          writes.finish(ride.id());
          for (var reservation : data.reservations()) {
            if (!Set.of("confirmed", "boarded").contains(reservation.status())) continue;
            var parcel = data.parcel(reservation);
            if (reservation.boardedAt() != null) awaitFinal(reservation, parcel, "fulfilled");
            else if (reservation.contract() != null && availability) {
              writes.countedAbsence(
                  reservation.id(),
                  "notified".equals(reservation.absence())
                      ? "notified_absence"
                      : "passenger_absent");
              awaitFinal(reservation, parcel, "fulfilled_absence");
              if (writes.absenceCount(reservation.contract()) == 3) {
                cases.record(
                    actor.id(),
                    reservation,
                    "Três ausências no mensal: análise sem punição automática.",
                    "absence_review");
                notices.notify(
                    reservation.passenger(),
                    "Ausências no mensal",
                    "O vínculo continua. Confira sua rotina e o registro de disponibilidade.",
                    "contract",
                    reservation.contract());
              }
            } else {
              financial.block(
                  parcel.getId(), "Sem registro de embarque ou disponibilidade cumprida");
              writes.review(reservation.id(), false);
              UUID report =
                  cases.record(
                      actor.id(),
                      reservation,
                      "Sem registro de embarque ou disponibilidade cumprida. Política financeira em análise.",
                      "dispute");
              cases.link(report, parcel.getId());
              notices.notify(
                  reservation.passenger(),
                  "Trecho em análise",
                  "A parcela está bloqueada para análise.",
                  "reservation",
                  reservation.id());
            }
          }
          audit.record(
              actor.id(),
              "ride_completed",
              "ride",
              ride.id(),
              Map.of("availability", availability));
          return status(id, "completed");
        },
        "availability",
        availability);
  }

  public LifecycleDto.Status confirmFinal(Actor actor, String id, String key) {
    return once(
        actor,
        id,
        key,
        "reservation:final",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.reservation(uuid(id), actor.id(), false);
          var reservation = data.reservation();
          var parcel = data.parcel();
          ApiException.require(
              "awaiting_confirmation".equals(reservation.status())
                  && "awaiting".equals(parcel.getStatus()),
              "FINAL_UNAVAILABLE",
              "Aguarde a declaração do motorista ou a análise.",
              409);
          boolean noClass = "no_class".equals(reservation.availability());
          financial.settle(
              parcel.getId(),
              noClass ? ParcelService.Outcome.CREDITED : ParcelService.Outcome.RELEASED,
              noClass
                  ? "Trecho não prestado por ausência de aula: crédito integral, elegível a devolução"
                  : "Confirmação final do passageiro",
              false);
          writes.finalConfirmed(reservation.id());
          audit(actor, "final_confirmed", "reservation", reservation.id());
          return status(id, "completed");
        });
  }

  public LifecycleDto.Status contest(Actor actor, String id, String reason, String key) {
    return once(
        actor,
        id,
        key,
        "reservation:contest",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.reservation(uuid(id), actor.id(), false);
          var reservation = data.reservation();
          var parcel = data.parcel();
          if (Set.of("future", "awaiting").contains(parcel.getStatus())) {
            financial.block(parcel.getId(), reason);
            writes.review(reservation.id(), true);
          }
          UUID report = cases.record(actor.id(), reservation, reason, "dispute");
          if ("blocked".equals(parcel.getStatus())) cases.link(report, parcel.getId());
          notices.notify(
              reservation.driver(),
              "Contestação registrada",
              "A parcela envolvida seguirá análise.",
              "reservation",
              reservation.id());
          audit(actor, "parcel_contested", "reservation", reservation.id());
          return status(report.toString(), "pending");
        },
        "reason",
        reason);
  }

  public LifecycleDto.Cancelled cancelPassenger(Actor actor, String id, String reason, String key) {
    return once(
        actor,
        id,
        key,
        "reservation:cancel",
        LifecycleDto.Cancelled.class,
        () -> {
          var data = locks.reservation(uuid(id), actor.id(), false);
          var reservation = data.reservation();
          ApiException.require(
              reservation.contract() == null
                  && "confirmed".equals(reservation.status())
                  && "planned".equals(data.data().ride().status()),
              "CANCEL_UNAVAILABLE",
              "Para o mensal, use o gerenciamento do plano.",
              409);
          financial.block(data.parcel().getId(), "Cancelamento pelo passageiro: " + reason);
          writes.cancel(reservation.id(), reason, true);
          UUID report =
              cases.record(
                  actor.id(),
                  reservation,
                  "Cancelamento pelo passageiro: " + reason + ". Política financeira em análise.",
                  "dispute");
          cases.link(report, data.parcel().getId());
          audit(actor, "passenger_cancelled", "reservation", reservation.id());
          return new LifecycleDto.Cancelled(id, "cancelled", report);
        },
        "reason",
        reason);
  }

  public LifecycleDto.Status cancelRide(Actor actor, String id, String reason, String key) {
    return once(
        actor,
        id,
        key,
        "ride:cancel",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.ride(uuid(id), actor.id(), true, true);
          var ride = data.ride();
          ApiException.require(
              Set.of("planned", "active").contains(ride.status()),
              "RIDE_STATE",
              "Este trecho já foi encerrado.",
              409);
          writes.rideState(ride.id(), "cancelled");
          for (var reservation : data.reservations()) {
            if (!Set.of("confirmed", "boarded", "awaiting_confirmation", "under_review")
                .contains(reservation.status())) continue;
            var parcel = data.parcel(reservation);
            if (Set.of("future", "awaiting").contains(parcel.getStatus()))
              financial.settle(
                  parcel.getId(),
                  ParcelService.Outcome.CREDITED,
                  "Cancelamento do motorista: " + reason,
                  false);
            writes.cancel(reservation.id(), reason, true);
            notices.notify(
                reservation.passenger(),
                "Trecho cancelado",
                "blocked".equals(parcel.getStatus())
                    ? "A parcela contestada continua em análise."
                    : "A parcela paga deste trecho voltou integralmente como crédito.",
                "reservation",
                reservation.id());
          }
          writes.cancelUnpaidAndReleaseHolds(ride.id(), reason);
          audit(actor, "driver_cancelled", "ride", ride.id());
          return status(id, "cancelled");
        },
        "reason",
        reason);
  }

  public LifecycleDto.Status noClass(Actor actor, String id, String reason, String key) {
    return once(
        actor,
        id,
        key,
        "ride:no-class",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.ride(uuid(id), actor.id(), true, false);
          ApiException.require(
              "planned".equals(data.ride().status()),
              "RIDE_STATE",
              "Informe Sem aula antes do início.",
              409);
          ApiException.require(
              data.reservations().stream()
                  .noneMatch(r -> r.contract() == null && ACTIVE.contains(r.status())),
              "AVULSA_CONFIRMED",
              "Há avulsas confirmadas. Cancele o trecho com crédito integral.",
              409);
          writes.rideState(data.ride().id(), "no_class");
          for (var reservation : data.reservations())
            if ("confirmed".equals(reservation.status()))
              awaitFinal(reservation, data.parcel(reservation), "no_class");
          audit.record(
              actor.id(), "ride_no_class", "ride", data.ride().id(), Map.of("reason", reason));
          return status(id, "no_class");
        },
        "reason",
        reason);
  }

  public LifecycleDto.Absence absence(Actor actor, String id, String reason, String key) {
    return once(
        actor,
        id,
        key,
        "reservation:absence",
        LifecycleDto.Absence.class,
        () -> {
          var data = locks.reservation(uuid(id), actor.id(), false);
          var reservation = data.reservation();
          ApiException.require(
              reservation.contract() != null
                  && "confirmed".equals(reservation.status())
                  && "planned".equals(data.data().ride().status()),
              "ABSENCE_UNAVAILABLE",
              "Avise ausência de um trecho mensal que ainda não começou.",
              409);
          writes.notifyAbsence(reservation.id(), reason);
          notices.notify(
              reservation.driver(),
              "Ausência avisada",
              "A vaga mensal permanece reservada e não pode ser revendida.",
              "passengers",
              reservation.ride());
          audit(actor, "monthly_absence_notice", "reservation", reservation.id());
          return new LifecycleDto.Absence(id, "notified");
        },
        "reason",
        reason);
  }

  public LifecycleDto.Status proposeTime(
      Actor actor, String id, String time, String reason, String key) {
    return once(
        actor,
        id,
        key,
        "ride:time",
        LifecycleDto.Status.class,
        () -> {
          var data = locks.ride(uuid(id), actor.id(), true, false);
          var ride = data.ride();
          planned(ride.status());
          ApiException.require(
              writes.future(ride, time), "DATE_PAST", "Escolha um horário futuro.", 422);
          var linked =
              data.reservations().stream().filter(r -> ACTIVE.contains(r.status())).toList();
          writes.propose(ride.id(), time, reason, !linked.isEmpty());
          for (var reservation : linked) {
            writes.reservationProposal(reservation.id(), time);
            notices.notify(
                reservation.passenger(),
                "Novo horário proposto",
                ride.time() + " → " + time + ". Confirme se poderá acompanhar.",
                "reservation",
                reservation.id());
          }
          audit.record(actor.id(), "ride_time_proposed", "ride", ride.id(), Map.of("time", time));
          return status(id, linked.isEmpty() ? "updated" : "awaiting_agreement");
        },
        "time",
        time,
        "reason",
        reason);
  }

  public LifecycleDto.Answer respondTime(Actor actor, String id, boolean accept, String key) {
    return once(
        actor,
        id,
        key,
        "reservation:time",
        LifecycleDto.Answer.class,
        () -> {
          var data = locks.reservation(uuid(id), actor.id(), false);
          var ride = data.data().ride();
          var reservation = data.reservation();
          ApiException.require(
              "planned".equals(ride.status())
                  && ride.proposedTime() != null
                  && "pending".equals(reservation.timeResponse()),
              "TIME_PROPOSAL_UNAVAILABLE",
              "Esta proposta já foi encerrada.",
              409);
          writes.answer(reservation.id(), accept);
          boolean cancelled = !accept && reservation.contract() == null;
          if (cancelled) {
            financial.settle(
                data.parcel().getId(),
                ParcelService.Outcome.CREDITED,
                "Novo horário recusado pelo avulso: cancelamento do motorista",
                false);
            writes.cancel(reservation.id(), "time_declined", false);
          }
          if (!accept && reservation.contract() != null)
            notices.notify(
                reservation.driver(),
                "Horário sem consenso",
                "Preserve o horário original ou cancele o trecho com crédito integral.",
                "passengers",
                ride.id());
          boolean consensus =
              data.data().reservations().stream()
                  .filter(
                      r ->
                          ACTIVE.contains(r.status())
                              && !(cancelled && r.id().equals(reservation.id())))
                  .allMatch(
                      r ->
                          r.id().equals(reservation.id())
                              ? accept
                              : "accepted".equals(r.timeResponse()));
          if (consensus) writes.acceptProposal(ride.id());
          audit.record(
              actor.id(),
              "ride_time_response",
              "reservation",
              reservation.id(),
              Map.of("accept", accept));
          return new LifecycleDto.Answer(id, accept ? "accepted" : "declined");
        },
        "accept",
        accept);
  }

  public LifecycleDto.Time keepTime(Actor actor, String id, String key) {
    return once(
        actor,
        id,
        key,
        "ride:keep-time",
        LifecycleDto.Time.class,
        () -> {
          var data = locks.ride(uuid(id), actor.id(), true, false);
          var ride = data.ride();
          planned(ride.status());
          writes.keepTime(ride.id());
          for (var reservation : data.reservations())
            if (ACTIVE.contains(reservation.status())) {
              writes.withdraw(reservation.id());
              notices.notify(
                  reservation.passenger(),
                  "Horário original preservado",
                  "O trecho continua às " + ride.time() + ".",
                  "reservation",
                  reservation.id());
            }
          audit(actor, "ride_time_preserved", "ride", ride.id());
          return new LifecycleDto.Time(id, ride.time());
        });
  }
}
