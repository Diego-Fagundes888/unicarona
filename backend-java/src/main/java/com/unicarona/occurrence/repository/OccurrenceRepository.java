package com.unicarona.occurrence.repository;

import com.unicarona.occurrence.entity.Occurrence;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OccurrenceRepository extends JpaRepository<Occurrence,UUID> {
    Optional<Occurrence> findFirstByReservationIdAndKindAndStatusIn(UUID reservation,String kind,Collection<String> states);
    @Modifying @Query(value="INSERT INTO occurrence_parcels(occurrence_id,parcel_id) VALUES(:report,:parcel) ON CONFLICT DO NOTHING",nativeQuery=true)
    int link(@Param("report") UUID report,@Param("parcel") UUID parcel);
}
