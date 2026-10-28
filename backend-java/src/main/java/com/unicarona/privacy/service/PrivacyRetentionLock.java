package com.unicarona.privacy.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared transaction lock for hold decisions and irreversible retention work, across API/worker processes. */
public final class PrivacyRetentionLock {
    private PrivacyRetentionLock(){}
    public static void acquire(JdbcTemplate sql){
        acquireForRecovery(sql);
        com.unicarona.shared.ApiException.require(!Boolean.TRUE.equals(sql.queryForObject("SELECT needs_confirmation FROM privacy_hold_control_state WHERE singleton",Boolean.class)),"PRIVACY_RECOVERY_PENDING","O serviço está confirmando um controle de preservação. Tente novamente após a recuperação.",503);
    }
    static void acquireForRecovery(JdbcTemplate sql){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Retention coordination requires a transaction.");
        sql.execute("SET LOCAL lock_timeout='3s'");sql.queryForList("SELECT pg_advisory_xact_lock(74019,1)");
    }
}
