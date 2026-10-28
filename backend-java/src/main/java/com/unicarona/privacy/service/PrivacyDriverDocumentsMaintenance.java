package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service @Order(85)
public class PrivacyDriverDocumentsMaintenance implements MaintenanceTask {
    private final PrivacyDriverDocuments documents;private final PrivacyFileErasure erasure;private UUID after;private long nextAttempt;
    public PrivacyDriverDocumentsMaintenance(PrivacyDriverDocuments documents,PrivacyFileErasure erasure){this.documents=documents;this.erasure=erasure;}
    public synchronized int runOnce(int batch,BooleanSupplier stopping)throws IOException {
        if(batch<1||batch>10000)throw new IllegalArgumentException("Invalid driver document batch.");var rows=documents.candidates(after,batch);if(rows.isEmpty()){after=null;return 0;}int changed=0;for(var row:rows){if(stopping.getAsBoolean())break;after=row.id();if(documents.retire(row,true))changed++;}return changed;
    }
    public void beforeReadiness()throws IOException {
        while(true){var rows=documents.candidates(null,500);if(rows.isEmpty())break;for(var row:rows)if(!documents.retire(row,false))throw new IOException("Driver document retirement could not confirm its recovery dependencies.");}
        while(true){var files=documents.unheldPreparedFiles(500);if(files.isEmpty())return;for(UUID file:files)erasure.erase(file);}
    }
    @Override public synchronized void run(int batch,BooleanSupplier stopping){if(stopping.getAsBoolean()||(nextAttempt!=0&&System.nanoTime()-nextAttempt<0))return;nextAttempt=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);try{runOnce(batch,stopping);}catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=driver_document_retirement_pending code=DRIVER_DOCUMENT_RETENTION_RETRY");}}
}
