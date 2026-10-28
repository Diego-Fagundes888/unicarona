package com.unicarona.privacy.dto;

import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class PrivacyHoldDto {
    private PrivacyHoldDto(){}
    public record Create(@NotBlank @Size(max=128)String password,@NotNull UUID userId,
        @NotBlank @Pattern(regexp="profile|files|location|contract_identity|vehicle_identity|driver_application|identity_change|account_decision|privacy_request|consent|legal|notifications|security_audit")String category,
        @NotNull UUID resourceId,@NotBlank @Pattern(regexp="legal_obligation|regular_exercise_of_rights|contract|security")String legalBasis,
        @NotBlank @Size(min=20,max=3000)String reason,@NotBlank @Size(min=3,max=160)String caseReference,
        @NotNull Instant reviewAt,@NotNull Instant expiresAt){
        public Create{reason=InputText.trim(reason);caseReference=InputText.trim(caseReference);}
        @Override public String toString(){return "CreatePrivacyHold[redacted]";}
    }
    public record Review(@NotBlank @Size(max=128)String password,@NotNull @Min(1)Integer expectedRevision,
        @NotBlank @Pattern(regexp="review|release")String action,@NotBlank @Size(min=20,max=3000)String reason,
        @NotBlank @Size(min=3,max=160)String caseReference,Instant reviewAt,Instant expiresAt){
        public Review{reason=InputText.trim(reason);caseReference=InputText.trim(caseReference);}
        @Override public String toString(){return "ReviewPrivacyHold[redacted]";}
    }
    public record Item(UUID id,UUID userId,String category,String resourceType,UUID resourceId,String legalBasis,
        int revision,String status,String createdAt,String reviewedAt,String reviewAt,String expiresAt,String releasedAt,boolean reviewDue){}
    public record Event(UUID id,int revision,String action,String createdAt,UUID administrator,String reason,String caseReference,String reviewAt,String expiresAt){@Override public String toString(){return "PrivacyHoldEvent[redacted]";}}
    public record Evidence(Item hold,String reason,String caseReference,List<Event> history){@Override public String toString(){return "PrivacyHoldEvidence[redacted]";}}
    public record OwnRetention(UUID id,String category,String legalBasis,String reviewAt,String expiresAt,String status){}
}
