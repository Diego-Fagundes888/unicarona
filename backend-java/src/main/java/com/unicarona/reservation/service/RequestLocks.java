package com.unicarona.reservation.service;

import com.unicarona.reservation.entity.RideRequest;
import com.unicarona.reservation.repository.*;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.ActorsRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RequestLocks {
    public record Locked(RideRequest request,List<ActorsRepository.Person> persons) {}
    private final ActorsRepository actors;
    private final RequestRulesRepository rules;
    private final RideRequestRepository requests;
    public RequestLocks(ActorsRepository actors,RequestRulesRepository rules,RideRequestRepository requests) { this.actors=actors;this.rules=rules;this.requests=requests; }
    @Transactional(propagation=Propagation.MANDATORY) public Locked lock(UUID id,UUID actor,RequestRulesRepository.Side side) {
        var reference=rules.participants(id,actor,side);var people=actors.lockUsers(List.of(reference.driver(),reference.passenger()));
        var request=requests.lockById(id).orElseThrow(() -> new ApiException("REQUEST_NOT_FOUND","Solicitação não encontrada.",404));return new Locked(request,people);
    }
}
