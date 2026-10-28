package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service @Order(180)
public class PrivacyTripDetailsMaintenance implements MaintenanceTask {
    private final PrivacyTripDetailsRetention retention;private UUID after;
    public PrivacyTripDetailsMaintenance(PrivacyTripDetailsRetention retention){this.retention=retention;}
    public synchronized int runOnce(int batch,BooleanSupplier stopping)throws IOException {var rows=retention.candidates(after,batch);if(rows.isEmpty()&&after!=null){after=null;rows=retention.candidates(null,batch);}int changed=0;for(var row:rows){if(stopping.getAsBoolean())break;after=row.route();if(retention.retire(row,true))changed++;}return changed;}
    public void beforeReadiness()throws IOException {while(true){var rows=retention.candidates(null,500);if(rows.isEmpty())return;for(var row:rows)if(!retention.retire(row,false)&&retention.candidates(null,500).contains(row))throw new IOException("Operational trip text could not be minimized before readiness.");}}
    @Override public synchronized void run(int batch,BooleanSupplier stopping){if(stopping.getAsBoolean())return;try{runOnce(batch,stopping);}catch(Exception pending){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=trip_text_retention_pending code=TRIP_TEXT_RETENTION_RETRY");}}
}
