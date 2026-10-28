package com.unicarona.privacy.dto;

import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.*;

public final class PrivacyDto {
    private PrivacyDto(){}
    public record Preferences(boolean marketing,String statementVersion,String statement){}
    public record PreferenceUpdate(@NotNull Boolean marketing,@Size(max=64) String statementVersion){}
    public record Request(@NotBlank @Pattern(regexp="access|correction|erasure|restriction|opposition|portability|appeal|other") String kind,
        @NotBlank @Size(min=3,max=2000) String details){
        public Request{details=InputText.trim(details);}
        @Override public String toString(){return "PrivacyRequest[redacted]";}
    }
    public record Protocol(UUID id,String kind,String status,String createdAt,String dueAt){}
    public record Response(String message,String createdAt){}
    public record Detail(Protocol request,String details,List<Response> responses){}
    public record Reauthenticate(@NotBlank @Size(max=128) String password){
        @Override public String toString(){return "PrivacyReauthentication[redacted]";}
    }
    public record ClosureRequest(@NotBlank @Size(max=128) String password,
        @NotNull @AssertTrue Boolean acknowledged,@NotBlank @Size(max=64) String policyVersion){
        @Override public String toString(){return "PrivacyClosureRequest[redacted]";}
    }
    public record Obligation(String code,String label,long count,boolean delaysErasure){}
    public record Closure(UUID id,String status,String requestedAt,String eraseAfter,boolean canCancel,
        int graceDays,String policyVersion,List<Obligation> obligations,Map<String,Long> balances,String initiatedBy){}
    public record AdminItem(Protocol request,UUID userId,String name,String email){}
    public record AdminDetail(AdminItem request,String details,List<Response> responses){}
    public record Review(@NotBlank @Pattern(regexp="received|in_review|awaiting_information") String expectedStatus,
        @NotBlank @Pattern(regexp="in_review|awaiting_information|completed|denied") String status,
        @NotBlank @Size(min=20,max=3000) String message,Boolean actionPerformed){
        public Review{message=InputText.trim(message);}
        @Override public String toString(){return "PrivacyReview[redacted]";}
    }
}
