package com.unicarona.payment.repository;

import com.unicarona.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment,UUID> {
    Optional<Payment> findByRequestId(UUID requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from Payment p where p.id=:id")
    Optional<Payment> lockById(@Param("id") UUID id);
    @Modifying(flushAutomatically=true) @Query(value="UPDATE payments SET status='expired',updated_at=now() WHERE request_id=:id AND status IN ('awaiting','pending')",nativeQuery=true)
    int expireForRequest(@Param("id") UUID id);
    @Modifying @Query(value="UPDATE payments SET provider_reference=:reference,checkout_url=:url,gateway_environment=:environment WHERE id=:id AND status='pending' AND provider_reference IS NULL",nativeQuery=true)
    int submitted(@Param("id") UUID id,@Param("reference") String reference,@Param("url") String url,@Param("environment") String environment);
}
