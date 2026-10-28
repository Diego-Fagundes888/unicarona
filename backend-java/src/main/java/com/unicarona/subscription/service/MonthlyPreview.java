package com.unicarona.subscription.service;

import com.unicarona.payment.service.PricingCalculator;
import com.unicarona.ride.repository.RideRulesRepository;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.ActorsRepository;
import com.unicarona.subscription.dto.SubscriptionDto;
import com.unicarona.subscription.repository.ScheduleRepository;
import java.util.*;
import com.unicarona.privacy.service.LegalDocumentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MonthlyPreview {
    private final ActorsRepository actors;
    private final LegalDocumentService legal;
    private final ScheduleRepository schedule;
    private final RideRulesRepository rules;
    private final PricingCalculator pricing;
    public MonthlyPreview(ActorsRepository actors,ScheduleRepository schedule,RideRulesRepository rules,PricingCalculator pricing,LegalDocumentService legal) { this.actors=actors;this.legal=legal;this.schedule=schedule;this.rules=rules;this.pricing=pricing; }
    @Transactional(propagation=Propagation.MANDATORY) public SubscriptionDto.Preview calculate(UUID actor,SubscriptionDto.MonthlyInput input,boolean locked) {
        return build(actor,input,locked);
    }
    /** Renewal owns a JDBC savepoint; an expected eligibility error must not mark the outer JPA transaction rollback-only. */
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=ApiException.class) public SubscriptionDto.Preview renewal(UUID actor,SubscriptionDto.MonthlyInput input) {
        return build(actor,input,true);
    }
    private SubscriptionDto.Preview build(UUID actor,SubscriptionDto.MonthlyInput input,boolean locked) {
        var reference=schedule.routine(UUID.fromString(input.routineId()));var people=actors.lockUsers(List.of(actor,reference.driver()));
        // A pause/vehicle review may commit while waiting for the ordered user locks.
        var routine=schedule.routine(reference.id());
        var person=people.stream().filter(p -> p.id().equals(actor)).findFirst().orElseThrow();var owner=people.stream().filter(p -> p.id().equals(routine.driver())).findFirst().orElseThrow();
        ActorsRepository.active(person);ActorsRepository.driver(owner);
        // A new renewal is a new commitment; viewing a preview and finishing paid rides remain available.
        if(locked){legal.requireCurrent(actor,false);legal.requireCounterparty(owner.id(),true);}
        ApiException.require(!person.id().equals(owner.id()) && "active".equals(routine.status()),"ROUTINE_UNAVAILABLE","Esta rotina não está disponível.",409);
        UUID previous=input.previousContractId()==null?null:UUID.fromString(input.previousContractId());
        ApiException.require(previous==null || schedule.canRenew(previous,actor,routine.id(),input.startDate()),"RENEWAL_UNAVAILABLE","Renovação indisponível.",409);
        actors.notBlocked(actor,owner.id());
        ApiException.require(ActorsRepository.sameClass(person,owner) && routine.classId().equals(person.classId()),"CLASS_MISMATCH","O mensal exige o mesmo curso, período e turma.",422);
        schedule.stabilizeCalendar(routine.university());var dates=schedule.dates(routine.zone(),input.startDate());
        ApiException.require(input.startDate().compareTo(dates.today())>=0,"DATE_PAST","Escolha início hoje ou em uma data futura.",422);
        var weekdays=input.weekdays().stream().distinct().sorted().toList();
        ApiException.require(!weekdays.isEmpty() && routine.weekdays().containsAll(weekdays),"INVALID_WEEKDAYS","Escolha dias disponíveis na rotina.",422);
        var directions="ambos".equals(input.directions())?List.of("ida","volta"):List.of(input.directions());
        ApiException.require(directions.stream().allMatch(d -> "ida".equals(d)?routine.idaTime()!=null:routine.voltaTime()!=null),"INVALID_DIRECTION","Escolha um sentido disponível.",422);
        schedule.materialize(routine.id(),input.startDate(),dates.end());
        var offers=schedule.eligible(routine,actor,person.classId(),input.startDate(),dates.end(),weekdays,directions,previous);
        var p=rules.currentPricing(routine.university());var price=pricing.allocateMonthly(offers.stream().map(ScheduleRepository.Offer::cents).toList(),p.params().discountBps());
        if(locked) schedule.lockRides(offers.stream().map(ScheduleRepository.Offer::id).toList());
        var lines=new ArrayList<SubscriptionDto.Line>();for(int i=0;i<offers.size();i++) { var r=offers.get(i);lines.add(new SubscriptionDto.Line(r.id(),r.date(),r.direction(),r.time(),price.allocations().get(i),r.commissionBps()/10000.0)); }
        return new SubscriptionDto.Preview(routine.id(),routine.driver(),person.classId(),input.startDate(),dates.end(),input.directions(),weekdays,price.originalCents(),price.totalCents(),p.params().discountBps()/10000.0,
            schedule.excluded(routine.university(),person.classId(),input.startDate(),dates.end()),List.copyOf(lines),offers.stream().filter(r -> !"planned".equals(r.status()) || r.available()<1 || r.alreadyReserved()).map(ScheduleRepository.Offer::id).toList());
    }
}
