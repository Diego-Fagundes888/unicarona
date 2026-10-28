package com.unicarona.subscription.repository;

import com.unicarona.subscription.entity.Routine;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RoutineRepository extends JpaRepository<Routine,UUID> {
    @Query(value="SELECT privacy_routine_details_visible(r) FROM routines r WHERE id=:id",nativeQuery=true)
    boolean detailsCurrent(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Routine r where r.id=:id and r.driverId=:driver")
    Optional<Routine> lockOwn(@Param("id") UUID id,@Param("driver") UUID driver);
    @Modifying(flushAutomatically=true) @Query(value="UPDATE routines SET status=:status,updated_at=now() WHERE id=:id",nativeQuery=true)
    int availability(@Param("id") UUID id,@Param("status") String status);
}
