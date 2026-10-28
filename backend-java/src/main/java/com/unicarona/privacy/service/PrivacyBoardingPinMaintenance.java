package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service @Order(150)
public class PrivacyBoardingPinMaintenance implements MaintenanceTask {
    private final PrivacyBoardingPinRetention retention;private UUID after;private long nextAttempt;
    public PrivacyBoardingPinMaintenance(PrivacyBoardingPinRetention retention){this.retention=retention;}
    public synchronized int runOnce(int batch,BooleanSupplier stopping){
        var rows=retention.candidates(after,batch);if(rows.isEmpty()&&after!=null){after=null;rows=retention.candidates(null,batch);}int changed=0;
        for(var row:rows){if(stopping.getAsBoolean())break;after=row.id();if(retention.retire(row,true))changed++;}return changed;
    }
    public void beforeReadiness()throws IOException {while(true){var rows=retention.candidates(null,500);if(rows.isEmpty())return;for(var row:rows)if(!retention.retire(row,false)&&retention.candidates(null,500).stream().anyMatch(r->r.id().equals(row.id())))throw new IOException("Expired boarding credential could not be suppressed before readiness.");}}
    @Override public synchronized void run(int batch,BooleanSupplier stopping){if(stopping.getAsBoolean()||(nextAttempt!=0&&System.nanoTime()-nextAttempt<0))return;nextAttempt=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);try{runOnce(batch,stopping);}catch(Exception pending){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=boarding_pin_retention_pending code=BOARDING_PIN_RETENTION_RETRY");}}
}
