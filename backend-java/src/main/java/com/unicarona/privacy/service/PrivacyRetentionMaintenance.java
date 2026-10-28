package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import org.springframework.stereotype.Service;
import java.util.function.BooleanSupplier;

@Service @org.springframework.core.annotation.Order(200)
public class PrivacyRetentionMaintenance implements MaintenanceTask {
    private final PrivacyRetentionTransactions transactions;private final PrivacyLocationRetentionTransactions locations;private final PrivacyHoldRecovery recovery;
    public PrivacyRetentionMaintenance(PrivacyRetentionTransactions transactions,PrivacyLocationRetentionTransactions locations,PrivacyHoldRecovery recovery){this.transactions=transactions;this.locations=locations;this.recovery=recovery;}
    @Override public void run(int batch,BooleanSupplier stopping){if(!stopping.getAsBoolean())locations.prune(batch);if(!stopping.getAsBoolean())transactions.prune(batch);if(!stopping.getAsBoolean())try{recovery.cleanup(batch);}catch(java.io.IOException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=privacy_hold_cleanup_pending code=CONTROL_CLEANUP_RETRY");}}
}
