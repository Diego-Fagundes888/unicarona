package com.unicarona.job.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.InputText;
import com.unicarona.shared.OptionalNonNullIntegerDeserializer;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class JobDto {
    private JobDto() {}
    public record Summary(int pending,int processing,int done,int dead) {}
    public record Item(UUID id,String type,String status,int attempts,String availableAt,String leasedUntil,
        String createdAt,String completedAt,String errorCode) {}
    public record Page(Summary summary,List<Item> items,Integer nextOffset) {}
    @JsonPropertyOrder({"expectedAttempts","reason"})
    public record Retry(@NotNull @Min(0) @Max(2147483646) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer expectedAttempts,
        @NotBlank @Size(min=3,max=1000) String reason) {
        public Retry {
            ApiException.require(reason==null || reason.length()<=1000,"VALIDATION_ERROR","Confira os campos informados.",422);
            reason=InputText.trim(reason);
        }
    }
    public record Retried(String id,String status,int attempts) {}
}
