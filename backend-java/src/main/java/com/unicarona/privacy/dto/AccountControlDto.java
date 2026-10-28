package com.unicarona.privacy.dto;

import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.*;

public final class AccountControlDto {
    private AccountControlDto(){}
    public record Deactivate(@NotBlank @Size(max=128)String password,@NotNull @AssertTrue Boolean acknowledged){
        @Override public String toString(){return "DeactivateAccount[redacted]";}
    }
    public record Decision(@NotBlank @Size(max=128)String password,
        @NotNull @Pattern(regexp="ban|close|lift_ban|cancel_closure")String action,
        @NotNull @Pattern(regexp="active|deactivated|suspended|banned|closing")String expectedStatus,
        @NotNull @Pattern(regexp="fraud|violence|harassment|false_documents|security|repeated_terms_breach")String reasonCode,
        @NotBlank @Size(min=10,max=1000)String publicReason,@NotBlank @Size(min=20,max=3000)String reason,
        @Min(1) @Max(365)Integer restrictionDays){
        public Decision{publicReason=InputText.trim(publicReason);reason=InputText.trim(reason);}
        @Override public String toString(){return "AccountDecision[redacted]";}
    }
    public record Notice(UUID id,String action,String status,String reasonCode,String message,String createdAt){}
    public record State(String status,boolean canReactivate,String restrictionUntil,PrivacyDto.Closure closure,List<Notice> decisions){}
    public record PrivateDecision(Notice notice,String internalReason){@Override public String toString(){return "PrivateAccountDecision[redacted]";}}
    public record Subject(UUID id,String name,String email){@Override public String toString(){return "AdministrativeAccountSubject[redacted]";}}
    public record AdminState(Subject subject,State account,List<PrivateDecision> decisions){@Override public String toString(){return "AdministrativeAccountState[redacted]";}}
}
