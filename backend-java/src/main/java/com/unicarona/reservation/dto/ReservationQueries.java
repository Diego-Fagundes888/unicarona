package com.unicarona.reservation.dto;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.ride.dto.RideQueries;
import com.unicarona.shared.*;
import java.util.*;

public record ReservationQueries(String side,UUID rideId,UUID contractId,boolean upcoming,Pagination page) {
    public static ReservationQueries parse(Map<String,String> query) {
        var page=Pagination.parse(query,Set.of("side","rideId","contractId","upcoming"));String side=query.getOrDefault("side","passenger");
        ApiException.require(Set.of("passenger","driver").contains(side),"VALIDATION_ERROR","Confira os campos informados.",422);
        return new ReservationQueries(side,id(query.get("rideId")),id(query.get("contractId")),RideQueries.bool(query,"upcoming"),page);
    }
    private static UUID id(String raw) {
        ApiException.require(raw==null || raw.matches(AuthDto.UUID_PATTERN),"VALIDATION_ERROR","Confira os campos informados.",422);return raw==null?null:UUID.fromString(raw);
    }
}
