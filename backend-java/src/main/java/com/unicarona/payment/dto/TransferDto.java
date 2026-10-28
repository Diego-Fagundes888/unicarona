package com.unicarona.payment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class TransferDto {
    private TransferDto() {}
    @JsonPropertyOrder({"type","cents"})
    public record Request(@NotNull @Pattern(regexp="withdraw|refund") String type,@NotNull @Min(1) @Max(100000000) Integer cents) {}
    public record Created(UUID id,String status) {}
    public record Item(UUID id,String type,int cents,String status,String cadence,String createdAt,String updatedAt) {}
    @JsonPropertyOrder({"eventId","transferId","reference","amountCents","status"})
    public record Event(@NotBlank @Size(max=128) @Pattern(regexp="[a-zA-Z0-9_-]+") String eventId,
        @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String transferId,@NotBlank @Size(max=200) String reference,
        @NotNull @Min(1) @Max(100000000) Integer amountCents,@NotNull @Pattern(regexp="approved|failed") String status) {
        public Event { eventId=InputText.trim(eventId);reference=InputText.trim(reference); }
    }
    public record Received(boolean received,boolean duplicate) {}
}
