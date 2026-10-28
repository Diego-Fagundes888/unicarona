package com.unicarona.payment.repository;

import com.unicarona.payment.entity.Transfer;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from Transfer t where t.id=:id")
    Optional<Transfer> lockById(@Param("id") UUID id);
    @Modifying @Query(value="UPDATE transfers SET provider_reference=:reference WHERE id=:id AND status='pending' AND provider_reference IS NULL",nativeQuery=true)
    int submitted(@Param("id") UUID id,@Param("reference") String reference);
}
