package com.unicarona.driver.service;

import com.unicarona.driver.dto.DriverDto;
import com.unicarona.driver.repository.DriverRepository;
import com.unicarona.driver.repository.DriverProfileRepository;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.security.Actor;
import com.unicarona.privacy.service.LegalDocumentService;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.IdempotencyExecutor;
import com.unicarona.shared.repository.ActorsRepository;
import com.unicarona.shared.repository.AuditRepository;
import com.unicarona.file.storage.PrivateFileStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverTransactions {
    private final ActorsRepository actors;
    private final LegalDocumentService legal;
    private final DriverRepository drivers;
    private final DriverProfileRepository profiles;
    private final IdempotencyExecutor idempotency;
    private final NotificationService notifications;
    private final AuditRepository audit;
    private final PrivateFileStorage storage;
    public DriverTransactions(ActorsRepository actors,DriverRepository drivers,DriverProfileRepository profiles,IdempotencyExecutor idempotency,NotificationService notifications,AuditRepository audit,LegalDocumentService legal,PrivateFileStorage storage) {
        this.actors=actors;this.legal=legal;this.drivers=drivers;this.idempotency=idempotency;this.notifications=notifications;this.audit=audit;
        this.profiles=profiles;this.storage=storage;
    }
    @Transactional public DriverDto.Status apply(Actor actor,DriverDto.Apply input,String key) {
        return idempotency.execute(actor.id(),key,"driver:apply",input,DriverDto.Status.class,() -> {
            var user=actors.lockUser(actor.id()); ActorsRepository.active(user);legal.requireCurrent(actor.id(),true);
            ApiException.require("user".equals(user.role()) && user.courseId()!=null,"PROFILE_REQUIRED","Complete o perfil acadêmico antes de solicitar aprovação.",422);
            ApiException.require(drivers.ownsDocuments(actor.id(),UUID.fromString(input.identityFileId()),UUID.fromString(input.licenseFileId())),"DOCUMENTS_REQUIRED","Envie sua identificação e habilitação.",422);
            var application=drivers.create(actor.id(),input);
            profiles.pending(actor.id());
            notifications.notify(actor.id(),"Cadastro em análise","Novas ofertas dependem da aprovação de motorista e veículo.","driver",null);
            audit.record(actor.id(),"driver_application","driver_application",application.id(),Map.of()); return application;
        });
    }
    @Transactional public DriverDto.Status review(Actor actor,String id,DriverDto.Review input,String key) {
        ApiException.require("admin".equals(actor.role()),"FORBIDDEN","Acesso administrativo necessário.",403);
        var hashInput=new LinkedHashMap<String,Object>(); hashInput.put("id",id);hashInput.put("approve",input.approve());hashInput.put("reason",input.reason());
        return idempotency.execute(actor.id(),key,"driver:review",hashInput,DriverDto.Status.class,() -> {
            UUID applicationId=UUID.fromString(id);
            UUID user=drivers.applicationUser(applicationId).orElseThrow(() -> new ApiException("APPLICATION_NOT_FOUND","Solicitação não encontrada.",404));
            ActorsRepository.active(actors.lockUser(user)); var application=drivers.lockApplication(applicationId);
            ApiException.require("pending".equals(application.status()),"APPLICATION_STATE","Solicitação já analisada ou substituída.",409);
            ApiException.require(drivers.documentsReviewable(application),"APPLICATION_DOCUMENTS_EXPIRED","O prazo desta solicitação terminou. O titular precisa enviar uma nova solicitação com os documentos.",409);
            ApiException.require(drivers.ownsDocuments(user,application.identity(),application.license()),"APPLICATION_DOCUMENTS_UNAVAILABLE","Os documentos não estão disponíveis para análise. Confira os arquivos antes de registrar uma decisão.",409);
            var documents=drivers.reviewDocuments(application);ApiException.require(documents.size()==2,"APPLICATION_DOCUMENTS_UNAVAILABLE","Os documentos não estão disponíveis para análise.",409);
            try{for(var document:documents){var bytes=storage.observe(document.id());ApiException.require(bytes.isPresent()&&bytes.get().bytes()==document.bytes()&&bytes.get().sha256().equals(document.sha256()),"APPLICATION_DOCUMENTS_UNAVAILABLE","Os documentos não puderam ser conferidos. Verifique os arquivos antes de registrar uma decisão.",409);}}
            catch(java.io.IOException unavailable){throw new ApiException("APPLICATION_DOCUMENTS_UNAVAILABLE","Não foi possível acessar os documentos. Tente novamente após conferir o armazenamento.",503);}
            if(input.approve()) drivers.approveVehicle(application);
            drivers.decide(application,actor.id(),input.approve(),input.reason());
            String status=input.approve()?"approved":"rejected";
            profiles.decide(user,status,input.approve());
            notifications.notify(user,input.approve()?"Motorista e veículo aprovados":"Cadastro precisa de revisão",input.reason(),"driver",null);
            audit.record(actor.id(),"driver_review","driver_application",applicationId,Map.of("outcome",status)); return new DriverDto.Status(applicationId,status);
        });
    }
}
