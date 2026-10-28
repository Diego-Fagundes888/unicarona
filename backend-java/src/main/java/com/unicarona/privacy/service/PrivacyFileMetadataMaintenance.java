package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service @Order(110)
public class PrivacyFileMetadataMaintenance implements MaintenanceTask {
    private final PrivacyFileMetadataRetention metadata;private UUID after;private long nextAttempt;
    public PrivacyFileMetadataMaintenance(PrivacyFileMetadataRetention metadata){this.metadata=metadata;}
    public synchronized int runOnce(int batch,BooleanSupplier stopping)throws IOException {if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid file metadata batch.");var rows=metadata.candidates(after,batch);if(rows.isEmpty()){after=null;return 0;}int changed=0;for(var row:rows){if(stopping.getAsBoolean())break;after=row.id();if(metadata.retire(row))changed++;}return changed;}
    @Override public synchronized void run(int batch,BooleanSupplier stopping){if(stopping.getAsBoolean()||(nextAttempt!=0&&System.nanoTime()-nextAttempt<0))return;nextAttempt=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);try{runOnce(batch,stopping);}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=file_metadata_retention_pending code=FILE_METADATA_RETENTION_RETRY");}}
}
