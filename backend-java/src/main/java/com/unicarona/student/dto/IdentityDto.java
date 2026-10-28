package com.unicarona.student.dto;

import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class IdentityDto {
    private IdentityDto() {}
    public record Settings(@NotNull Boolean notices,@NotNull @Pattern(regexp="daily|monthly") String withdrawal) {}
    public record Review(@NotNull Boolean approve,@NotBlank @Size(min=3,max=2000) String reason) {
        public Review { reason=InputText.trim(reason); }
    }
    public record Verification(String status,String source) {}
    public record Decision(UUID id,String status,String source) {}
    public record QueueItem(UUID id,String name,String university,String course,Integer periodNumber,String classLabel) {}
}
