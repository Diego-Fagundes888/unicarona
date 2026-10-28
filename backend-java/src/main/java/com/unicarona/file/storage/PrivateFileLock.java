package com.unicarona.file.storage;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cross-process coordination between physical upload, metadata commit and orphan cleanup.
 * Hash collisions only serialize unrelated files; they do not grant access to either file. */
public final class PrivateFileLock {
    private PrivateFileLock(){}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Private file coordination requires a transaction.");}
    public static void acquire(JdbcTemplate sql,UUID file){transaction();sql.execute("SET LOCAL lock_timeout='3s'");sql.queryForList("SELECT pg_advisory_xact_lock(74020,?)",file.hashCode());}
    public static boolean tryAcquire(JdbcTemplate sql,UUID file){transaction();return Boolean.TRUE.equals(sql.queryForObject("SELECT pg_try_advisory_xact_lock(74020,?)",Boolean.class,file.hashCode()));}
}
