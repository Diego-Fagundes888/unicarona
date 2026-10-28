package com.unicarona.user.service;

import com.unicarona.auth.repository.AuthRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.repository.*;
import com.unicarona.subscription.repository.SubscriptionRepository;
import com.unicarona.subscription.service.FutureContractClosure;
import com.unicarona.user.dto.*;
import com.unicarona.user.repository.*;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileTransactions {
    private final ProfileChangeRepository changes;
    private final ProfileRepository profiles;
    private final ActorsRepository users;
    private final AuthRepository academic;
    private final SubscriptionRepository contracts;
    private final FutureContractClosure closure;
    private final AuditRepository audit;
    private final IdempotencyExecutor keys;
    public ProfileTransactions(ProfileChangeRepository changes,ProfileRepository profiles,ActorsRepository users,AuthRepository academic,SubscriptionRepository contracts,FutureContractClosure closure,AuditRepository audit,IdempotencyExecutor keys) {
        this.changes=changes;this.profiles=profiles;this.users=users;this.academic=academic;this.contracts=contracts;this.closure=closure;this.audit=audit;this.keys=keys;
    }
    @Transactional public ProfileDto update(Actor actor,ProfileEditDto.Update input,String key) {
        return keys.execute(actor.id(),key,"profile:update",input.fingerprint(),ProfileDto.class,() -> {
            ApiException.require(!"admin".equals(actor.role()),"PROFILE_UNAVAILABLE","Este perfil não possui vínculo acadêmico.",422);
            var people=new ArrayList<UUID>();people.add(actor.id());changes.participants(actor.id()).forEach(p -> { people.add(p.driver());people.add(p.passenger()); });users.lockUsers(people);
            var locked=new HashSet<>(people);if(changes.participants(actor.id()).stream().anyMatch(p -> !locked.contains(p.driver()) || !locked.contains(p.passenger()))) throw new ConcurrencyFailureException("Profile participants changed before locking",new SQLException("Retry with current participants","40001"));
            var original=profiles.find(actor.id());var next=input.academic();
            boolean changed=!Objects.equals(original.universityId(),UUID.fromString(next.universityId())) || !Objects.equals(original.courseId(),UUID.fromString(next.courseId())) || !Objects.equals(original.periodStatus(),next.periodStatus()) || !Objects.equals(original.periodNumber(),"numbered".equals(next.periodStatus())?next.periodNumber():null) || !Objects.equals(original.classLabel(),next.classLabel()==null?"":next.classLabel());
            var ids=changes.contracts(actor.id());ApiException.require(!changed || ids.isEmpty() || input.acknowledgeMonthlyImpact(),"MONTHLY_IMPACT_REQUIRED","Confirme o encerramento dos vínculos mensais incompatíveis e o crédito das parcelas futuras.",409);
            academic.saveAcademic(actor.id(),next.input());changes.identity(actor.id(),input.name(),input.phone());
            if(changed) { for(UUID id:ids) closure.close(contracts.findById(id).orElseThrow(),actor.id(),"Dados acadêmicos alterados; vínculo mensal incompatível.",FutureContractClosure.Outcome.CREDITED);changes.pauseIncompatible(actor.id()); }
            audit.record(actor.id(),"profile_updated","user",actor.id(),Map.of("classChanged",changed,"closedContracts",changed?ids.size():0));return profiles.find(actor.id());
        });
    }
}
