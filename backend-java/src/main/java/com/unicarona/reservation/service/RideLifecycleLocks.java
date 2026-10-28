package com.unicarona.reservation.service;

import com.unicarona.payment.entity.Parcel;
import com.unicarona.payment.repository.ParcelRepository;
import com.unicarona.reservation.repository.LifecycleRepository;
import com.unicarona.reservation.repository.LifecycleRepository.*;
import com.unicarona.shared.repository.ActorsRepository;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Transactional(propagation=Propagation.MANDATORY)
public class RideLifecycleLocks {
    public record Locked(Ride ride,List<Reservation> reservations,Map<UUID,Parcel> parcels) {
        public Locked { reservations=List.copyOf(reservations);parcels=Map.copyOf(parcels); }
        public Parcel parcel(Reservation reservation) { return parcels.get(reservation.id()); }
    }
    public record ForReservation(Locked data,Reservation reservation) { public Parcel parcel() { return data.parcel(reservation); } }
    private final LifecycleRepository reads;
    private final ActorsRepository actors;
    private final ParcelRepository parcels;
    public RideLifecycleLocks(LifecycleRepository reads,ActorsRepository actors,ParcelRepository parcels) { this.reads=reads;this.actors=actors;this.parcels=parcels; }
    public Locked ride(UUID id,UUID actor,boolean own,boolean existingClosure) {
        UUID driver=reads.driver(id,actor,own);var users=new ArrayList<>(reads.passengers(id));users.add(driver);
        var people=actors.lockUsers(users);
        // A paid confirmation can have committed while these ordered user locks were waiting.
        // Retry the whole transaction instead of adding a newly discovered user out of order.
        if(!new HashSet<>(users).containsAll(reads.passengers(id))) throw new ConcurrencyFailureException("Ride participants changed before locking",new SQLException("Retry with current participants","40001"));
        if(own && !existingClosure) ActorsRepository.existingDriver(people.stream().filter(p -> p.id().equals(actor)).findFirst().orElseThrow());
        var ride=reads.lockRide(id);var reservations=reads.lockReservations(id);var ids=reads.lockParcels(reservations);
        var money=parcels.findAllById(ids).stream().collect(Collectors.toMap(Parcel::getReservationId,p -> p));return new Locked(ride,reservations,money);
    }
    public ForReservation reservation(UUID id,UUID actor,boolean driver) {
        UUID ride=reads.reservationRide(id,actor,driver);var data=ride(ride,actor,driver,false);
        // UUID comparison also handles the uppercase-ID bug in the original in-memory lookup.
        return new ForReservation(data,data.reservations().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
    }
}
