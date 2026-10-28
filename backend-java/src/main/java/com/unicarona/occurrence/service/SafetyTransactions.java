package com.unicarona.occurrence.service;

import com.unicarona.occurrence.dto.SafetyDto;
import com.unicarona.occurrence.entity.Occurrence;
import com.unicarona.occurrence.repository.*;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.payment.service.ParcelService;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import com.unicarona.subscription.repository.SubscriptionRepository;
import com.unicarona.subscription.service.FutureContractClosure;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class SafetyTransactions {
    private final SafetyRepository rules;
    private final ActorsRepository users;
    private final OccurrenceRepository cases;
    private final UserBlockRepository blocks;
    private final SubscriptionRepository contracts;
    private final FutureContractClosure closure;
    private final ParcelService financial;
    private final ParcelRepository parcels;
    private final IdempotencyExecutor keys;
    private final AuditRepository audit;
    private final NotificationService notices;
    public SafetyTransactions(SafetyRepository rules,ActorsRepository users,OccurrenceRepository cases,UserBlockRepository blocks,
        SubscriptionRepository contracts,FutureContractClosure closure,ParcelService financial,ParcelRepository parcels,IdempotencyExecutor keys,AuditRepository audit,NotificationService notices) {
        this.rules=rules;this.users=users;this.cases=cases;this.blocks=blocks;this.contracts=contracts;this.closure=closure;this.financial=financial;this.parcels=parcels;this.keys=keys;this.audit=audit;this.notices=notices;
    }
    public SafetyDto.Status report(Actor actor,SafetyDto.Report input,String key) {
        return keys.execute(actor.id(),key,"safety:report",input,SafetyDto.Status.class,() -> {
            ApiException.require(!"admin".equals(actor.role()),"PROFILE_NOT_FOUND","Selecione um perfil de aluno.",404);
            UUID other=UUID.fromString(input.otherId());rules.related(actor.id(),other);users.lockUsers(List.of(actor.id(),other));
            var reservation=input.reservationId()==null?null:rules.reservation(UUID.fromString(input.reservationId()),actor.id(),other);
            UUID report=cases.saveAndFlush(new Occurrence(actor.id(),other,reservation==null?null:reservation.id(),input.reason(),input.severe())).getId();
            if(reservation!=null && input.severe()) {
                var parcel=rules.lockReservation(reservation);
                if(Set.of("future","awaiting","blocked").contains(parcel.status())) { financial.block(parcel.id(),input.reason());cases.link(report,parcel.id());rules.underReview(reservation.id()); }
            }
            if(input.block() || input.severe()) interrupt(actor.id(),other,report);
            audit.record(actor.id(),"safety_reported","occurrence",report,Map.of("severe",input.severe()));notices.notify(actor.id(),"Relato recebido","A ocorrência e eventuais parcelas afetadas seguirão análise.","safety",report);
            parcels.flush();return new SafetyDto.Status(report.toString(),"pending");
        });
    }
    private void interrupt(UUID actor,UUID other,UUID report) {
        blocks.activate(actor,other);
        for(UUID id:rules.contracts(actor,other)) {
            var contract=contracts.findById(id).orElseThrow();
            for(UUID parcel:closure.close(contract,actor,"Vínculo interrompido por segurança; parcelas futuras em análise.",FutureContractClosure.Outcome.BLOCKED)) cases.link(report,parcel);
        }
        rules.cancelUnpaid(actor,other);audit.record(actor,"personal_block","user",other,Map.of());
    }
    public SafetyDto.Removed unblock(Actor actor,String rawId) {
        UUID id=UUID.fromString(rawId),other=rules.blockOwner(id,actor.id());users.lockUsers(List.of(actor.id(),other));blocks.deactivate(id);
        audit.record(actor.id(),"personal_unblock","user",other,Map.of());return new SafetyDto.Removed(rawId,false);
    }
    public SafetyDto.Reviewed review(Actor actor,String rawId,SafetyDto.Review input,String key) {
        admin(actor);var fingerprint=new LinkedHashMap<String,Object>();fingerprint.put("id",rawId);fingerprint.put("outcome",input.outcome());fingerprint.put("reason",input.reason());if(input.acknowledgedDecisionIds()!=null) fingerprint.put("acknowledgedDecisionIds",input.acknowledgedDecisionIds());
        return keys.execute(actor.id(),key,"safety:review",fingerprint,SafetyDto.Reviewed.class,() -> {
            UUID id=UUID.fromString(rawId);var ref=rules.report(id,false);var refs=rules.participants(id);var people=new ArrayList<UUID>();people.add(ref.reporter());if(ref.other()!=null) people.add(ref.other());refs.forEach(p -> { people.add(p.passenger());people.add(p.driver()); });users.lockUsers(people);
            var report=rules.report(id,true);ApiException.require(Set.of("pending","financial_pending").contains(report.status()),"OCCURRENCE_STATE","Ocorrência já analisada.",409);
            rules.lockRides(refs.stream().map(SafetyRepository.Participants::ride).toList());var affected=rules.lockParcels(id);
            ApiException.require(affected.isEmpty()?"no_financial_change".equals(input.outcome()):!"no_financial_change".equals(input.outcome()),"OUTCOME_INVALID","Selecione um resultado coerente com as parcelas da ocorrência.",422);
            if("retained".equals(input.outcome())) ApiException.require(affected.stream().allMatch(p -> "no_class".equals(p.availability())),"OUTCOME_INVALID","Retenção integral se aplica somente a Sem aula posterior ao contrato.",422);
            var conflicting=rules.conflicts(affected,id,input.outcome());var acknowledged=input.acknowledgedDecisionIds()==null?List.<String>of():input.acknowledgedDecisionIds().stream().distinct().sorted().toList();
            if(!conflicting.equals(acknowledged)) throw new ApiException("FINANCIAL_DECISION_CONFLICT","Há decisões financeiras divergentes. Revise os casos relacionados e confirme quais decisões serão substituídas para as parcelas ainda bloqueadas.",409,Map.of("conflictingDecisionIds",conflicting));
            rules.decide(id,input.outcome(),input.reason(),actor.id());int pending=0;
            for(var parcel:affected) {
                if(!"blocked".equals(parcel.status())) continue;
                if(rules.otherPending(parcel.id(),id)) { pending++;continue; }
                financial.settle(parcel.id(),ParcelService.Outcome.valueOf(input.outcome().toUpperCase(Locale.ROOT)),input.reason(),true);rules.completed(parcel.reservation());
            }
            audit.record(actor.id(),"occurrence_reviewed","occurrence",id,Map.of("outcome",input.outcome(),"pendingOtherCases",pending,"acknowledgedDecisionIds",acknowledged));notices.notify(ref.reporter(),"Ocorrência analisada",input.reason(),"safety",id);parcels.flush();return new SafetyDto.Reviewed(rawId,"resolved",pending);
        });
    }
    public static void admin(Actor actor) { ApiException.require("admin".equals(actor.role()),"FORBIDDEN","Acesso administrativo necessário.",403); }
}
