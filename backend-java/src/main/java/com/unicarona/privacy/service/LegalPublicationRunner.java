package com.unicarona.privacy.service;

import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component @Order(-100)
public class LegalPublicationRunner implements ApplicationRunner {
    private final LegalDocumentTemplates templates;private final LegalPublicationService publisher;private final org.springframework.core.env.Environment env;
    public LegalPublicationRunner(LegalDocumentTemplates templates,LegalPublicationService publisher,org.springframework.core.env.Environment env){this.templates=templates;this.publisher=publisher;this.env=env;}
    @Override public void run(ApplicationArguments args){
        if("published".equals(templates.publicationState())){publisher.publish(templates.prepare());publisher.notifyChanges();}
        else if("production".equals(env.getProperty("NODE_ENV")))throw new IllegalStateException("Draft legal documents cannot be used for production readiness.");
    }
}
