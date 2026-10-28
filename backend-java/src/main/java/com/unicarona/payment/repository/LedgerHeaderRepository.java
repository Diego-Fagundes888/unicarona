package com.unicarona.payment.repository;

import com.unicarona.payment.entity.LedgerHeader;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerHeaderRepository extends JpaRepository<LedgerHeader,UUID> {
    Optional<LedgerHeader> findByEventKey(String eventKey);
}
