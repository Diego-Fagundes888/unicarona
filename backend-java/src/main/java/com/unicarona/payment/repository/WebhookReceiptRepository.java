package com.unicarona.payment.repository;

import com.unicarona.payment.entity.WebhookReceipt;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from WebhookReceipt r where r.id=:id")
    Optional<WebhookReceipt> lockById(@Param("id") UUID id);
}
