package com.unicarona.driver.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class DriverDto {
    private DriverDto() {}
    @JsonPropertyOrder({"model","color","plate","capacity","identityFileId","licenseFileId"})
    public record Apply(@NotBlank @Size(max=80) String model,@NotBlank @Size(max=40) String color,
        @NotNull @Pattern(regexp="[A-Z]{3}[0-9][A-Z0-9][0-9]{2}") String plate,@NotNull @Min(1) @Max(8) Integer capacity,
        @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String identityFileId,@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String licenseFileId) {
        public Apply { model=InputText.trim(model);color=InputText.trim(color);plate=InputText.plate(plate); }
    }
    public record Review(@NotNull Boolean approve,@NotBlank @Size(min=3,max=1200) String reason) {
        public Review { reason=InputText.trim(reason); }
    }
    public record Status(UUID id,String status) {}
    public record Application(UUID id,UUID userId,String status,String decision,String createdAt,String reviewedAt,
        UUID identityFileId,UUID licenseFileId,String model,String color,String plate,int capacity,String name,
        String documentsStatus,String documentsExpiresAt,String documentsPolicyVersion) {}
}
