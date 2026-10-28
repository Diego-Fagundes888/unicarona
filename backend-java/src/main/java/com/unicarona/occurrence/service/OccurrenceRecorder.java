package com.unicarona.occurrence.service;

import com.unicarona.occurrence.entity.Occurrence;
import com.unicarona.occurrence.repository.OccurrenceRepository;
import com.unicarona.reservation.repository.LifecycleRepository.Reservation;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The caller holds the ride/participants; repeated unresolved domain cases are reused. */
@Service @Transactional(propagation=Propagation.MANDATORY)
public class OccurrenceRecorder {
    private final OccurrenceRepository cases;
    public OccurrenceRecorder(OccurrenceRepository cases) { this.cases=cases; }
    public UUID record(UUID reporter,Reservation reservation,String reason,String kind) {
        var existing=cases.findFirstByReservationIdAndKindAndStatusIn(reservation.id(),kind,List.of("pending","financial_pending"));
        if(existing.isPresent()) return existing.get().getId();
        UUID other=reservation.passenger().equals(reporter)?reservation.driver():reservation.passenger();
        return cases.saveAndFlush(new Occurrence(reporter,other,reservation.id(),reason,kind)).getId();
    }
    public void link(UUID report,UUID parcel) { cases.link(report,parcel); }
}
