package com.unicarona.job.service;

import com.unicarona.job.repository.MaintenanceRepository;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.reservation.repository.RequestRulesRepository;
import com.unicarona.reservation.service.*;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.AuditRepository;
import com.unicarona.subscription.dto.SubscriptionDto;
import com.unicarona.subscription.repository.*;
import com.unicarona.subscription.service.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceTransactions {
    private final MaintenanceRepository reads;private final RequestLocks requests;private final RideLifecycleLocks rides;private final ContractLocks contracts;private final ParcelService money;private final ParcelRepository parcels;private final SubscriptionRepository subscriptions;private final SubscriptionScheduleRepository schedules;private final MonthlyPreview preview;private final RequestRulesRepository availability;private final NotificationService notices;private final AuditRepository audit;private final Clock clock;
    public MaintenanceTransactions(MaintenanceRepository reads,RequestLocks requests,RideLifecycleLocks rides,ContractLocks contracts,ParcelService money,ParcelRepository parcels,SubscriptionRepository subscriptions,SubscriptionScheduleRepository schedules,MonthlyPreview preview,RequestRulesRepository availability,NotificationService notices,AuditRepository audit,Clock clock) { this.reads=reads;this.requests=requests;this.rides=rides;this.contracts=contracts;this.money=money;this.parcels=parcels;this.subscriptions=subscriptions;this.schedules=schedules;this.preview=preview;this.availability=availability;this.notices=notices;this.audit=audit;this.clock=clock; }
    @Transactional public boolean request(MaintenanceRepository.Request ref) {
        var request=requests.lock(ref.id(),ref.passenger(),RequestRulesRepository.Side.PASSENGER).request();if(!reads.due(ref.id())) return false;reads.expireRequest(ref.id());notices.notify(request.getPassengerId(),"Pedido expirado","O prazo de confirmação terminou. Faça uma nova busca.","requests",ref.id());audit.record(null,"request_expired","request",ref.id(),Map.of());return true;
    }
    @Transactional public boolean finalConfirmation(MaintenanceRepository.Final ref) {
        var data=rides.ride(ref.ride(),null,false,true);var reservation=data.reservations().stream().filter(r -> r.id().equals(ref.id())).findFirst().orElseThrow();var parcel=data.parcel(reservation);
        if(!"awaiting_confirmation".equals(reservation.status()) || !"awaiting".equals(parcel.getStatus()) || reservation.deadline()==null || reservation.deadline().isAfter(Instant.now())) return false;
        boolean noClass="no_class".equals(reservation.availability());money.settle(parcel.getId(),noClass?ParcelService.Outcome.CREDITED:ParcelService.Outcome.RELEASED,noClass?"Trecho não prestado por ausência de aula: crédito integral, elegível a devolução":"Prazo de confirmação final encerrado sem contestação",false);parcels.flush();reads.timeoutFinal(reservation.id());audit.record(null,"final_confirmation_timeout","reservation",reservation.id(),Map.of());return true;
    }
    @Transactional public boolean subscription(UUID id) {
        var contract=contracts.lock(id,null,"participant").contract();var period=reads.period(id);if(!period.ended()) return false;var now=clock.instant();
        if(!period.renew() || !period.deadline().isAfter(now) || period.grace()!=null && !period.grace().isAfter(now) || "cancelled_renewal".equals(period.status())) {
            if(!Set.of("active","grace","renew_conflict","cancelled_renewal").contains(period.status())) return false;schedules.releaseHolds(id);subscriptions.ended(id,period.renew()?"expired":"ended");notices.notify(contract.getPassengerId(),"Período mensal encerrado","As vagas de períodos não pagos foram liberadas. O histórico pago permanece.","contract",id);return true;
        }
        if(!"active".equals(period.status())) return false;reads.graceDeadline(id,period.deadline());reads.savepoint();boolean held=false;
        try {
            var input=renewalInput(contract,period.start());var agenda=preview.renewal(contract.getPassengerId(),input);var lines=availabilityFor(agenda);if(!agenda.lines().isEmpty() && availability.linesAvailable(lines,contract.getPassengerId(),id)) { for(var line:agenda.lines()) reads.hold(id,line.rideId(),line.cents());held=true; }reads.releaseSavepoint();
        }catch(ApiException expected) { reads.rollbackSavepoint(); }
        reads.grace(id,held);notices.notify(contract.getPassengerId(),held?"Carência de renovação":"Renovação exige revisão",held?"As vagas foram retidas por até 72 horas após o vencimento. Trechos novos exigem pagamento.":"Não foi possível reter toda a agenda. Nenhuma reserva paga de outra pessoa foi alterada.","contract",id);audit.record(null,"subscription_grace","subscription",id,Map.of("held",held));return true;
    }
    private SubscriptionDto.MonthlyInput renewalInput(com.unicarona.subscription.entity.Subscription contract,String start) { return new SubscriptionDto.MonthlyInput(contract.getRoutineId().toString(),start,contract.getDirections(),contract.getWeekdays(),contract.getId().toString()); }
    private List<RequestRulesRepository.Offer> availabilityFor(SubscriptionDto.Preview agenda) { return agenda.lines().stream().sorted(Comparator.comparing(l -> l.rideId().toString())).map(l -> availability.lockRide(l.rideId())).toList(); }
}
