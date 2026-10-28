package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import com.unicarona.shared.TransactionRetries;
import java.util.function.BooleanSupplier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;

@Service @Order(90)
public class PrivacyUnusedFilesMaintenance implements MaintenanceTask {
    private final PrivacyUnusedFiles files;private final TransactionRetries retries;
    public PrivacyUnusedFilesMaintenance(PrivacyUnusedFiles files,TransactionRetries retries){this.files=files;this.retries=retries;}
    @Override public void run(int batch,BooleanSupplier stopping){
        if(stopping.getAsBoolean())return;
        for(var file:files.candidates(batch)){if(stopping.getAsBoolean())return;try{retries.call(()->files.retire(file));}catch(Exception failure){LoggerFactory.getLogger(getClass()).warn("event=unused_file_retirement_pending code=FILE_RETIREMENT_RETRY");return;}}
    }
}
