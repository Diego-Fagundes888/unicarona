package com.unicarona.reservation.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.ride.dto.RideQueries;
import com.unicarona.shared.*;
import com.unicarona.subscription.dto.SubscriptionDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.*;

public final class RequestDto {
    private RequestDto() {}
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,include=JsonTypeInfo.As.EXISTING_PROPERTY,property="type",visible=true)
    @JsonSubTypes({@JsonSubTypes.Type(value=Avulsa.class,name="avulsa"),@JsonSubTypes.Type(value=Mensal.class,name="mensal")})
    @Schema(name="RideRequestCreate",oneOf={Avulsa.class,Mensal.class},discriminatorProperty="type")
    public sealed interface Create permits Avulsa,Mensal { String type(); }
    @JsonPropertyOrder({"type","rideId"})
    @Schema(name="RideRequestAvulsa")
    public record Avulsa(@NotNull @Pattern(regexp="avulsa") @Schema(allowableValues={"avulsa"}) String type,
        @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String rideId) implements Create {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"type","routineId","startDate","directions","weekdays","previousContractId"})
    @Schema(name="RideRequestMensal")
    public record Mensal(@NotNull @Pattern(regexp="mensal") @Schema(allowableValues={"mensal"}) String type,
        @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String routineId,@NotNull @Pattern(regexp="[0-9]{4}-[0-9]{2}-[0-9]{2}") String startDate,
        @NotNull @Pattern(regexp="ida|volta|ambos") String directions,
        @NotNull @Size(min=1,max=7) @JsonDeserialize(contentUsing=OptionalNonNullIntegerDeserializer.class) List<@NotNull @Min(0) @Max(6) Integer> weekdays,
        @Pattern(regexp=AuthDto.UUID_PATTERN) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String previousContractId) implements Create {
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isDateValid() { return RideQueries.validDate(startDate); }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isWeekdaysValid() { return weekdays==null || new HashSet<>(weekdays).size()==weekdays.size(); }
        public SubscriptionDto.MonthlyInput monthly() { return new SubscriptionDto.MonthlyInput(routineId,startDate,directions,weekdays,previousContractId); }
    }
    @Schema(name="RideRequestRespond") public record Respond(@NotNull Boolean accept) {}
    @Schema(name="RideRequestCancel") public record Empty() {}
    @Schema(name="RideRequestCreated") public record Created(UUID id,String status,UUID paymentId) {}
    @Schema(name="RideRequestResponded") public record Responded(String id,String status,UUID paymentId) {}
    @Schema(name="RideRequestCancelled") public record Cancelled(String id,String status) {}
    @Schema(name="RideRequestOther") public record Other(UUID id,String name) {}
    @Schema(name="RideRequestItem") public record Item(UUID id,String type,String status,UUID rideId,UUID routineId,String createdAt,String expiresAt,UUID paymentId,String paymentStatus,Integer totalCents,Other other,String region,String date,String time) {}
    @Schema(name="RideRequestLine") public record Line(UUID rideId,String date,String time,String direction,int cents) {}
    @Schema(name="RideRequestDetail") public record Detail(UUID id,String type,String status,UUID driverId,UUID passengerId,UUID routineId,UUID rideId,UUID paymentId,String paymentStatus,long totalCents,String otherName,String expiresAt,List<Line> lines) {}
}
