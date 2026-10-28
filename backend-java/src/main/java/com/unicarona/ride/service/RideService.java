package com.unicarona.ride.service;

import com.unicarona.ride.dto.*;
import com.unicarona.ride.repository.RideReadRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideService {
    private final RideTransactions transactions;
    private final RideReadRepository reads;
    private final TransactionRetries retries;
    public RideService(RideTransactions transactions,RideReadRepository reads,TransactionRetries retries) { this.transactions=transactions;this.reads=reads;this.retries=retries; }
    public RideDto.Created create(Actor actor,RideDto.Create input,String key) { return retries.call(() -> transactions.create(actor,input,key)); }
    public RideDto.Edited edit(Actor actor,String id,RideDto.Edit input,String key) { return retries.call(() -> transactions.edit(actor,id,input,key)); }
    @Transactional(readOnly=true) public PageDto<RideDto.Item> search(Actor actor,RideQueries.Search input) {
        UUID university=reads.university(actor.id()).orElseThrow(() -> new ApiException("PROFILE_REQUIRED","Complete seu perfil acadêmico.",422));
        return PageDto.from(reads.search(actor.id(),university,input),input.page().limit(),input.page().offset());
    }
    @Transactional(readOnly=true) public PageDto<RideDto.Item> mine(Actor actor,RideQueries.Mine query) { return PageDto.from(reads.mine(actor.id(),query.page().limit()+1,query.page().offset(),query.upcoming()),query.page().limit(),query.page().offset()); }
    @Transactional(readOnly=true) public RideDto.Item detail(Actor actor,String id) {
        var snapshot=reads.detail(actor.id(),UUID.fromString(id));var ride=snapshot.item();
        boolean accepted=reads.acceptedRequest(ride.id(),actor.id());
        boolean linked=ride.driverId().equals(actor.id()) || reads.linked(ride.id(),actor.id()) || accepted;
        if(!linked) {
            ApiException.require(reads.university(actor.id()).filter(ride.universityId()::equals).isPresent() && "planned".equals(ride.status()) && "active".equals(snapshot.accountStatus()) && "approved".equals(snapshot.driverStatus()),"RIDE_NOT_FOUND","Carona não encontrada.",404);
            ApiException.require(!reads.blocked(actor.id(),ride.driverId()),"RIDE_NOT_FOUND","Carona não encontrada.",404);
        }
        if(ride.driverId().equals(actor.id())) return snapshot.own();
        if(linked && reads.operational(ride.id(),actor.id())) return snapshot.full();
        if(accepted) return snapshot.beforePayment();
        return linked?snapshot.protectedHistory():ride;
    }
}
