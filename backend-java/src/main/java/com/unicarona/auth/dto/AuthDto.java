package com.unicarona.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.Locale;
import com.unicarona.shared.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.unicarona.shared.OptionalNonNullStringDeserializer;

public final class AuthDto {
    private AuthDto() {}
    public record Message(String message) {}
    public record VerificationRequest(String message,int retryAfterSeconds) {}
    public record Empty() {}
    public record EmailInput(@NotBlank @Email @Pattern(regexp=EMAIL_PATTERN) @Size(max=254) String email) {
        public EmailInput { email = lower(email); }
    }
    public record Login(@NotBlank @Email @Pattern(regexp=EMAIL_PATTERN) @Size(max=254) String email, @NotNull @Size(min=1,max=128) String password) {
        public Login { email = lower(email); }
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Token(@NotBlank @Size(max=100) String token) {
        public Token { token = trim(token); }
        @Override public String toString() { return "Token[redacted]"; }
    }
    public record Reset(@NotBlank @Size(max=100) String token, @NotNull @Size(min=12,max=128) String password) {
        public Reset { token = trim(token); }
        @Override public String toString() { return "Reset[redacted]"; }
    }
    public record Change(@NotNull @Size(min=1,max=128) String currentPassword, @NotNull @Size(min=12,max=128) String password) {
        @Override public String toString() { return "Change[redacted]"; }
    }
    public record Academic(
        @NotNull @Pattern(regexp=UUID_PATTERN) String universityId,
        @NotNull @Pattern(regexp=UUID_PATTERN) String courseId,
        @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) @Pattern(regexp=UUID_PATTERN) String regionId,
        @NotBlank @Size(max=100) String region,
        @NotNull @Pattern(regexp="numbered|irregular|undefined") String periodStatus,
        @Min(1) @Max(100) Integer periodNumber,
        @NotNull @Pattern(regexp="morning|afternoon|night|full-time|variable") String shift,
        @Pattern(regexp="A|B|C") String classLabel) {
        public Academic { region = trim(region); }
        @AssertTrue(message="Informe o número do período.")
        @JsonIgnore
        public boolean isNumberedPeriodValid() { return !"numbered".equals(periodStatus) || periodNumber != null; }
    }
    public record Register(@NotBlank @Size(min=2,max=120) String name, @NotBlank @Email @Pattern(regexp=EMAIL_PATTERN) @Size(max=254) String email,
        @NotNull @Size(min=12,max=128) String password, @NotNull @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String birth,
        @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String phone, @NotNull @AssertTrue Boolean truth, @NotNull @AssertTrue Boolean terms, @NotNull @Valid Academic academic,
        @NotNull @Valid com.unicarona.privacy.dto.LegalDto.Registration legal) {
        public Register {
            name = trim(name); email = lower(email);
            if (phone != null) {
                if (phone.length() > 25) throw invalid();
                phone = phone.replaceAll("\\D", "");
                if (!phone.isEmpty() && (phone.length() < 10 || phone.length() > 15)) throw invalid();
            }
        }
        @AssertTrue(message="Informe uma data válida.")
        @JsonIgnore
        public boolean isBirthValid() {
            if (birth == null) return true;
            try { return LocalDate.parse(birth).toString().equals(birth); } catch (RuntimeException ignored) { return false; }
        }
        @Override public String toString() { return "Register[redacted]"; }
    }
    public static final String UUID_PATTERN = "(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)";
    public static final String EMAIL_PATTERN = "^(?:[A-Za-z0-9_'+\\-]+\\.)*[A-Za-z0-9_'+\\-]*[A-Za-z0-9_+-]@(?:[A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$";
    private static String trim(String value) {
        // Match JavaScript String.trim(), including NBSP/BOM, without trimming non-ECMAScript controls.
        return value == null ? null : value.replaceAll("^[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$", "");
    }
    private static String lower(String value) { return value == null ? null : trim(value).toLowerCase(Locale.ROOT); }
    private static ApiException invalid() { return new ApiException("VALIDATION_ERROR", "Confira os campos informados.", 422); }
}
