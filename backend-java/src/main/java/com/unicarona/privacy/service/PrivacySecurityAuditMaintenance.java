package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service @Order(125)
public class PrivacySecurityAuditMaintenance implements MaintenanceTask {
    private final PrivacySecurityAuditRetention retention;private UUID afterId;private long nextAttempt;
    public PrivacySecurityAuditMaintenance(PrivacySecurityAuditRetention retention){this.retention=retention;}
    public synchronized int runOnce(int batch,BooleanSupplier stopping)throws IOException {
        var rows=retention.candidates(afterId,batch);if(rows.isEmpty()){afterId=null;return 0;}int changed=0;for(var row:rows){if(stopping.getAsBoolean())break;afterId=row.id();if(retention.retire(row,true))changed++;}return changed;
    }
    public void beforeReadiness()throws IOException {while(true){var rows=retention.candidates(null,500);if(rows.isEmpty())return;for(var row:rows)if(!retention.retire(row,false))throw new IOException("Security audit expiry could not confirm its dependencies.");}}
    @Override public synchronized void run(int batch,BooleanSupplier stopping){if(stopping.getAsBoolean()||(nextAttempt!=0&&System.nanoTime()-nextAttempt<0))return;nextAttempt=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);try{runOnce(batch,stopping);}catch(Exception pending){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=security_audit_retention_pending code=SECURITY_AUDIT_RETENTION_RETRY");}}
}
