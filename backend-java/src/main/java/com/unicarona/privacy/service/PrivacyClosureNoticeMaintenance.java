package com.unicarona.privacy.service;

import com.unicarona.job.service.MaintenanceTask;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/** Preparing a notice cannot roll back a completed erasure or starve payment/mail maintenance. */
@Service @Order(150)
public class PrivacyClosureNoticeMaintenance implements MaintenanceTask {
    private final PrivacyClosureMail mail;
    public PrivacyClosureNoticeMaintenance(PrivacyClosureMail mail){this.mail=mail;}
    @Override public void run(int batch,BooleanSupplier stopping){
        for(var id:mail.due(batch)){if(stopping.getAsBoolean())return;
            try{mail.enqueueCompleted(id);}catch(Exception unavailable){mail.failed(id);LoggerFactory.getLogger(getClass()).warn("event=closure_notice_pending code=NOTICE_PREPARATION_RETRY");}
        }
    }
}
