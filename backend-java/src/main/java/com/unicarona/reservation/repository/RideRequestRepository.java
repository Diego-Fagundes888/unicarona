package com.unicarona.reservation.repository;

import com.unicarona.reservation.entity.RideRequest;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RideRequestRepository extends JpaRepository<RideRequest,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RideRequest r where r.id=:id")
    Optional<RideRequest> lockById(@Param("id") UUID id);
}
