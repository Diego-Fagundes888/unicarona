package com.unicarona.ride.repository;

import com.unicarona.ride.entity.Ride;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RideRepository extends JpaRepository<Ride,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Ride r where r.id=:id and r.driverId=:driver")
    Optional<Ride> lockOwn(@Param("id") UUID id,@Param("driver") UUID driver);
}
