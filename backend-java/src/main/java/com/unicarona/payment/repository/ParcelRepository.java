package com.unicarona.payment.repository;

import com.unicarona.payment.entity.Parcel;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ParcelRepository extends JpaRepository<Parcel,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Parcel p where p.id=:id")
    Optional<Parcel> lockById(@Param("id") UUID id);
}
