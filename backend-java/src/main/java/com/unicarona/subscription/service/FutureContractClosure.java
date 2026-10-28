package com.unicarona.subscription.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.shared.repository.AuditRepository;
import com.unicarona.subscription.entity.Subscription;
import com.unicarona.subscription.repository.*;
import com.unicarona.shared.ApiException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Caller has already locked both participants and the contract, in that order. */
@Service
public class FutureContractClosure {
    public enum Outcome { CREDITED, BLOCKED; public String value() { return name().toLowerCase(Locale.ROOT); } }
    private final ContractClosureRepository reads;
    private final ParcelService financial;
    private final ParcelRepository parcels;
    private final SubscriptionRepository contracts;
    private final SubscriptionScheduleRepository schedules;
    private final NotificationService notices;
    private final AuditRepository audit;
    public FutureContractClosure(ContractClosureRepository reads,ParcelService financial,ParcelRepository parcels,SubscriptionRepository contracts,
        SubscriptionScheduleRepository schedules,NotificationService notices,AuditRepository audit) {
        this.reads=reads;this.financial=financial;this.parcels=parcels;this.contracts=contracts;this.schedules=schedules;this.notices=notices;this.audit=audit;
    }
    @Transactional(propagation=Propagation.MANDATORY) public void closeLinkedPeriods(Subscription selected,UUID actor,String reason) {
        // Participant locks also serialize a payment that might create the next period.
        var periods=reads.linkedOpenPeriods(selected.getId());
        ApiException.require(periods.contains(selected.getId()),"SUBSCRIPTION_STATE","O mensal já foi encerrado.",409);
        reads.cancelUnpaidRenewals(periods);
        for(UUID id:periods) close(contracts.lockById(id).orElseThrow(),actor,reason,Outcome.CREDITED);
    }
    @Transactional(propagation=Propagation.MANDATORY) public List<UUID> close(Subscription contract,UUID actor,String reason,Outcome outcome) {
        var reservations=reads.reservations(contract.getId(),reads.futureRides(contract.getId()));
        var money=reads.parcels(reservations).stream().collect(Collectors.toMap(ContractClosureRepository.Parcel::reservation,p -> p));var affected=new ArrayList<UUID>();
        for(UUID reservation:reservations) {
            var parcel=money.get(reservation);
            if(Set.of("future","awaiting").contains(parcel.status())) {
                if(outcome==Outcome.BLOCKED) { financial.block(parcel.id(),reason);affected.add(parcel.id()); }
                else financial.settle(parcel.id(),ParcelService.Outcome.CREDITED,reason,false);
            }
            reads.cancelReservation(reservation,reason);
        }
        parcels.flush();schedules.releaseHolds(contract.getId());contracts.ended(contract.getId(),outcome==Outcome.BLOCKED?"interrupted":"ended");
        notices.notify(contract.getPassengerId(),"Vínculo mensal encerrado",outcome==Outcome.BLOCKED?"Parcelas futuras seguem análise separada.":"Os trechos futuros foram encerrados. Confira o crédito na carteira; valores em análise aguardam revisão.","contract",contract.getId());
        audit.record(actor,"subscription_future_closed","subscription",contract.getId(),Map.of("outcome",outcome.value(),"affected",reservations.size()));return List.copyOf(affected);
    }
}
