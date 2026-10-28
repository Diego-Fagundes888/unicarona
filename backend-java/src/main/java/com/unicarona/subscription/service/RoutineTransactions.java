package com.unicarona.subscription.service;

import com.unicarona.notification.service.NotificationService;
import com.unicarona.payment.service.PricingCalculator;
import com.unicarona.ride.entity.RideRoute;
import com.unicarona.ride.repository.*;
import com.unicarona.security.Actor;
import com.unicarona.privacy.service.LegalDocumentService;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import com.unicarona.subscription.dto.SubscriptionDto;
import com.unicarona.subscription.entity.Routine;
import com.unicarona.subscription.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineTransactions {
    private final ActorsRepository actors;
    private final LegalDocumentService legal;
    private final RideRouteRepository routes;
    private final RideRulesRepository rules;
    private final RoutineRepository routines;
    private final ScheduleRepository schedule;
    private final PricingCalculator pricing;
    private final IdempotencyExecutor idempotency;
    private final AuditRepository audit;
    private final NotificationService notifications;
    private final MonthlyPreview monthly;
    public RoutineTransactions(ActorsRepository actors,RideRouteRepository routes,RideRulesRepository rules,RoutineRepository routines,ScheduleRepository schedule,PricingCalculator pricing,IdempotencyExecutor idempotency,AuditRepository audit,NotificationService notifications,MonthlyPreview monthly,LegalDocumentService legal) {
        this.actors=actors;this.legal=legal;this.routes=routes;this.rules=rules;this.routines=routines;this.schedule=schedule;this.pricing=pricing;this.idempotency=idempotency;this.audit=audit;this.notifications=notifications;this.monthly=monthly;
    }
    @Transactional public SubscriptionDto.Created create(Actor actor,SubscriptionDto.RoutineInput input,String key) {
        return idempotency.execute(actor.id(),key,"routine:create",input,SubscriptionDto.Created.class,() -> {
            var person=actors.lockUser(actor.id());ActorsRepository.driver(person);legal.requireCurrent(actor.id(),true);
            ApiException.require(person.classId()!=null,"CLASS_REQUIRED","Informe curso, período numérico e turma para oferecer mensal.",422);
            var vehicle=rules.currentVehicle(actor.id());ApiException.require(vehicle.isPresent() && input.capacity()<=vehicle.get().capacity(),"INVALID_CAPACITY","Vagas excedem o veículo.",422);
            var p=rules.currentPricing(person.universityId());int distance=Math.toIntExact(Math.round(input.distance()*1000));
            String raw=input.toll()==null?"0":input.toll().isTextual()?input.toll().textValue():JavaScriptNumbers.format(input.toll().doubleValue());
            int price=Math.toIntExact(pricing.quote(distance,pricing.inputCents(raw),input.capacity(),p.params()));
            var route=routes.saveAndFlush(new RideRoute(person.universityId(),actor.id(),input.region(),input.meeting(),distance));
            var routine=routines.saveAndFlush(new Routine(route.getId(),actor.id(),person.universityId(),vehicle.get().id(),person.classId(),p.id(),price,input));
            var dates=schedule.dates(person.universityId());schedule.materialize(routine.getId(),dates.today(),dates.end());
            audit.record(actor.id(),"routine_published","routine",routine.getId(),Map.of("priceCents",price));return new SubscriptionDto.Created(routine.getId(),price);
        });
    }
    @Transactional public SubscriptionDto.Preview preview(Actor actor,SubscriptionDto.MonthlyInput input) { return monthly.calculate(actor.id(),input,false); }
    @Transactional public SubscriptionDto.State availability(Actor actor,String id,boolean enabled) {
        ActorsRepository.driver(actors.lockUser(actor.id()));if(enabled) legal.requireCurrent(actor.id(),true);
        var routine=routines.lockOwn(UUID.fromString(id),actor.id()).orElseThrow(() -> new ApiException("ROUTINE_NOT_FOUND","Rotina não encontrada.",404));
        if(enabled)ApiException.require(routines.detailsCurrent(routine.getId()),"ROUTINE_DETAILS_EXPIRED","Os detalhes desta rotina antiga venceram. Publique uma nova rotina.",409);
        boolean reopening=enabled && "paused".equals(routine.getStatus());String status=enabled?"active":"paused";
        routines.availability(routine.getId(),status);
        if(reopening) for(UUID user:schedule.waitingToNotify(routine.getId(),routine.getClassId(),actor.id())) notifications.notify(user,"Rotina reaberta","Confira toda a agenda antes de solicitar. Este aviso não garante vaga.","monthly",routine.getId());
        audit.record(actor.id(),"routine_availability","routine",routine.getId(),Map.of("active",enabled));return new SubscriptionDto.State(id,status);
    }
}
