package com.unicarona.ride.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.*;
import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class RideDto {
    private RideDto() {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name="RideCreateInput")
    @JsonPropertyOrder({"region","meeting","direction","date","time","capacity","distance","toll","automatic","notes"})
    public record Create(
        @NotBlank @Size(max=100) String region,@NotBlank @Size(max=200) String meeting,
        @NotNull @Pattern(regexp="ida|volta") String direction,@NotNull @Pattern(regexp="[0-9]{4}-[0-9]{2}-[0-9]{2}") String date,
        @NotNull @Pattern(regexp="([01][0-9]|2[0-3]):[0-5][0-9]") String time,
        @NotNull @Min(1) @Max(8) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer capacity,
        @NotNull @DecimalMin("0.1") @DecimalMax("2000") @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) Double distance,
        @JsonDeserialize(using=TollDeserializer.class) @JsonSerialize(using=JavaScriptNumbers.ScalarSerializer.class) @Schema(oneOf={Double.class,String.class}) JsonNode toll,
        @NotNull Boolean automatic,@Size(max=1200) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String notes) {
        public Create { region=InputText.trim(region);meeting=InputText.trim(meeting);notes=InputText.trim(notes); }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isDateValid() { return RideQueries.validDate(date); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"capacity","distance","toll","automatic","notes","version"})
    public record Edit(@NotNull @Min(1) @Max(8) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer capacity,
        @NotNull @DecimalMin("0.1") @DecimalMax("2000") @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) Double distance,
        @JsonDeserialize(using=TollDeserializer.class) @JsonSerialize(using=JavaScriptNumbers.ScalarSerializer.class) @Schema(oneOf={Double.class,String.class}) JsonNode toll,
        @NotNull Boolean automatic,@Size(max=1200) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String notes,
        @Positive @JsonDeserialize(using=OptionalNonNullDoubleDeserializer.class) @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) Double version) {
        public Edit { notes=InputText.trim(notes); }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isVersionValid() { return version==null || version==Math.rint(version); }
    }
    public record Created(UUID id,int priceCents) {}
    public record Edited(String id,int priceCents,int version) {}
    public record Vehicle(String model,String color,String plate,int capacity) {}
    public record Driver(UUID id,String name,String course,
        @Schema(description="numbered/undefined nos próprios dados; protected quando dados acadêmicos de outra pessoa não são compartilhados",allowableValues={"numbered","undefined","protected"}) String periodStatus,
        Integer periodNumber,String classLabel) {}
    public record Item(UUID id,UUID driverId,String routineId,String direction,String date,String time,UUID universityId,String region,
        String origin,String destination,String meeting,int capacity,long available,int priceCents,@JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double commission,boolean automatic,
        @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double distance,@JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double toll,String status,String notes,int version,String proposedTime,Vehicle vehicle,Driver driver) {}
}
