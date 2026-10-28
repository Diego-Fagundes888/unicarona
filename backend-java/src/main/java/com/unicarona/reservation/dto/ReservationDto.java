package com.unicarona.reservation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public final class ReservationDto {
    private ReservationDto() {}
    @Schema(name="ReservationVehicle") public record Vehicle(String model,String color,String plate) {}
    @Schema(name="Reservation")
    public record Item(UUID id,UUID rideId,UUID passengerId,UUID driverId,String contractId,String status,String rideStatus,
        String date,String time,String direction,String region,String meeting,String name,int priceCents,String parcelStatus,
        String availability,String absence,String finalResponse,String deadline,String proposedTime,String timeResponse,
        @JsonInclude(JsonInclude.Include.NON_NULL) String photo,Vehicle vehicle,@JsonInclude(JsonInclude.Include.NON_NULL) String pin) {
        @Override public String toString() { return "Reservation[id="+id+", status="+status+", pin=redacted]"; }
    }
    @Schema(name="ReservationContact") public record Contact(String name,String phone) {}
}
