package com.unicarona.subscription.service;

import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import com.unicarona.subscription.dto.ContractDto;
import com.unicarona.subscription.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractTransactions {
    private final ContractLocks locks;
    private final FutureContractClosure closure;
    private final SubscriptionRepository contracts;
    private final SubscriptionScheduleRepository schedules;
    private final WaitlistRepository waitlist;
    private final ActorsRepository actors;
    private final IdempotencyExecutor idempotency;
    private final AuditRepository audit;
    public ContractTransactions(ContractLocks locks,FutureContractClosure closure,SubscriptionRepository contracts,SubscriptionScheduleRepository schedules,
        WaitlistRepository waitlist,ActorsRepository actors,IdempotencyExecutor idempotency,AuditRepository audit) {
        this.locks=locks;this.closure=closure;this.contracts=contracts;this.schedules=schedules;this.waitlist=waitlist;this.actors=actors;this.idempotency=idempotency;this.audit=audit;
    }
    @Transactional public ContractDto.Status cancelRenewal(Actor actor,String id,String key) {
        return idempotency.execute(actor.id(),key,"subscription:cancel-renewal",Map.of("id",id),ContractDto.Status.class,() -> {
            var contract=locks.lock(UUID.fromString(id),actor.id(),"passenger").contract();open(contract.getStatus());
            contracts.ended(contract.getId(),"cancelled_renewal");schedules.releaseHolds(contract.getId());audit.record(actor.id(),"subscription_renewal_cancelled","subscription",contract.getId(),Map.of());
            return new ContractDto.Status(id,"cancelled_renewal");
        });
    }
    @Transactional public ContractDto.Status remove(Actor actor,String id,String reason,String key) {
        var fingerprint=new LinkedHashMap<String,Object>();fingerprint.put("id",id);fingerprint.put("reason",reason);
        return idempotency.execute(actor.id(),key,"subscription:remove",fingerprint,ContractDto.Status.class,() -> {
            var locked=locks.lock(UUID.fromString(id),actor.id(),"driver");ActorsRepository.existingDriver(locked.persons().stream().filter(p -> p.id().equals(actor.id())).findFirst().orElseThrow());
            var contract=locked.contract();closure.closeLinkedPeriods(contract,actor.id(),reason);waitlist.prioritize(contract.getPassengerId(),contract.getRoutineId());return new ContractDto.Status(id,"ended");
        });
    }
    @Transactional public ContractDto.Status wait(Actor actor,String routineId,String key) {
        return idempotency.execute(actor.id(),key,"subscription:wait",Map.of("routineId",routineId),ContractDto.Status.class,() -> {
            UUID id=UUID.fromString(routineId);var routine=waitlist.routine(id).orElseThrow(() -> new ApiException("ROUTINE_NOT_FOUND","Rotina não encontrada.",404));
            var people=actors.lockUsers(List.of(actor.id(),routine.driver()));people.forEach(ActorsRepository::active);
            ApiException.require(!actor.id().equals(routine.driver()) && ActorsRepository.sameClass(people.getFirst(),people.getLast()) && people.stream().filter(p -> p.id().equals(actor.id())).findFirst().orElseThrow().classId().equals(routine.classId()),"CLASS_MISMATCH","Confira a compatibilidade acadêmica.",422);
            actors.notBlocked(actor.id(),routine.driver());UUID saved=waitlist.join(actor.id(),id);audit.record(actor.id(),"waitlist_joined","routine",id,Map.of());return new ContractDto.Status(saved.toString(),"waiting");
        });
    }
    private static void open(String status) { ApiException.require(Set.of("active","grace","renew_conflict","cancelled_renewal").contains(status),"SUBSCRIPTION_STATE","O mensal já foi encerrado.",409); }
}
