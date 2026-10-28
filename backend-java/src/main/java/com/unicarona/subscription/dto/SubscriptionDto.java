package com.unicarona.subscription.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.*;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.ride.dto.*;
import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.*;

public final class SubscriptionDto {
    private SubscriptionDto() {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"region","meeting","direction","time","returnTime","weekdays","capacity","distance","toll","automatic","notes"})
    public record RoutineInput(@NotBlank @Size(max=100) String region,@NotBlank @Size(max=200) String meeting,
        @NotNull @Pattern(regexp="ida|volta|ambos") String direction,@NotNull @Pattern(regexp="([01][0-9]|2[0-3]):[0-5][0-9]") String time,
        @Pattern(regexp="([01][0-9]|2[0-3]):[0-5][0-9]") @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String returnTime,
        @NotNull @Size(min=1,max=7) @JsonDeserialize(contentUsing=OptionalNonNullIntegerDeserializer.class) List<@NotNull @Min(0) @Max(6) Integer> weekdays,
        @NotNull @Min(1) @Max(8) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer capacity,
        @NotNull @DecimalMin("0.1") @DecimalMax("2000") @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) Double distance,
        @JsonDeserialize(using=TollDeserializer.class) @JsonSerialize(using=JavaScriptNumbers.ScalarSerializer.class) @Schema(oneOf={Double.class,String.class}) JsonNode toll,
        @NotNull Boolean automatic,@Size(max=1200) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String notes) {
        public RoutineInput { region=InputText.trim(region);meeting=InputText.trim(meeting);notes=InputText.trim(notes); }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isReturnTimeValid() { return !"ambos".equals(direction) || returnTime!=null; }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isWeekdaysValid() { return weekdays==null || new HashSet<>(weekdays).size()==weekdays.size(); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"routineId","startDate","directions","weekdays","previousContractId"})
    public record MonthlyInput(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String routineId,@NotNull @Pattern(regexp="[0-9]{4}-[0-9]{2}-[0-9]{2}") String startDate,
        @NotNull @Pattern(regexp="ida|volta|ambos") String directions,
        @NotNull @Size(min=1,max=7) @JsonDeserialize(contentUsing=OptionalNonNullIntegerDeserializer.class) List<@NotNull @Min(0) @Max(6) Integer> weekdays,
        @Pattern(regexp=AuthDto.UUID_PATTERN) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String previousContractId) {
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isDateValid() { return RideQueries.validDate(startDate); }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isWeekdaysValid() { return weekdays==null || new HashSet<>(weekdays).size()==weekdays.size(); }
    }
    public record Availability(@NotNull Boolean enabled) {}
    public record State(String id,String status) {}
    public record Created(UUID id,int priceCents) {}
    public record Distances(@JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double ida,@JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double volta) {}
    public record Routine(UUID id,UUID driverId,String region,String meeting,List<Integer> weekdays,Map<String,String> times,Map<String,Integer> prices,
        Distances distances,int capacity,boolean automatic,String status,String notes,boolean compatible,RideDto.Driver driver) {}
    public record Exclusion(String date,String title) {}
    public record Line(UUID rideId,String date,String direction,String time,long cents,@JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double commission) {}
    public record Preview(UUID routineId,UUID driverId,UUID classId,String startDate,String endDate,String directions,List<Integer> weekdays,long originalCents,long totalCents,
        @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double discount,List<Exclusion> excludedDates,List<Line> lines,List<UUID> conflicts) {}
}
