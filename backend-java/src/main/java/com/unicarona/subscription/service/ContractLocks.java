package com.unicarona.subscription.service;

import com.unicarona.subscription.entity.Subscription;
import com.unicarona.subscription.repository.*;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.ActorsRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContractLocks {
    public record Locked(Subscription contract,List<ActorsRepository.Person> persons) {}
    private final ActorsRepository actors;
    private final ContractClosureRepository rules;
    private final SubscriptionRepository contracts;
    public ContractLocks(ActorsRepository actors,ContractClosureRepository rules,SubscriptionRepository contracts) { this.actors=actors;this.rules=rules;this.contracts=contracts; }
    @Transactional(propagation=Propagation.MANDATORY) public Locked lock(UUID id,UUID actor,String side) {
        var ref=rules.participants(id,actor,side);var people=actors.lockUsers(List.of(ref.driver(),ref.passenger()));
        var contract=contracts.lockById(id).orElseThrow(() -> new ApiException("SUBSCRIPTION_NOT_FOUND","Mensalidade não encontrada.",404));return new Locked(contract,people);
    }
}
