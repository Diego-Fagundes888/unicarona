package com.unicarona.privacy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.unicarona.auth.dto.AuthDto;
import jakarta.validation.constraints.*;
import java.util.*;

public final class IdentityCorrectionDto {
    private IdentityCorrectionDto(){}
    public record EmailRequest(@NotBlank @Size(max=128)String password,
        @NotBlank @Email @Pattern(regexp=AuthDto.EMAIL_PATTERN) @Size(max=254)String email){
        public EmailRequest{email=email==null?null:email.strip().toLowerCase(Locale.ROOT);}
        @Override public String toString(){return "EmailCorrectionRequest[redacted]";}
    }
    public record EmailConfirm(@NotBlank @Size(max=128)String password,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}")String token){
        @Override public String toString(){return "EmailCorrectionConfirmation[redacted]";}
    }
    public record Birth(@NotBlank @Size(max=128)String password,@NotNull @Pattern(regexp="[0-9]{4}-[0-9]{2}-[0-9]{2}")String birth){
        @AssertTrue @JsonIgnore public boolean isCalendarDate(){try{return birth!=null&&java.time.LocalDate.parse(birth).toString().equals(birth);}catch(java.time.DateTimeException invalid){return false;}}
        @Override public String toString(){return "BirthCorrection[redacted]";}
    }
    public record PendingEmail(UUID id,String maskedEmail,String expiresAt){}
    public record State(PendingEmail pendingEmail){}
    public record Accepted(boolean accepted,String message){}
    public record Completed(String status,String message){}
}
