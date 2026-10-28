package com.unicarona.subscription.dto;

import com.unicarona.ride.dto.RideQueries;
import com.unicarona.shared.*;
import java.util.*;

public final class RoutineQueries {
    private RoutineQueries() {}
    public record Search(String region,String direction,String time,boolean compatibleOnly,Pagination page) {}
    public static Search parse(Map<String,String> query) {
        var page=Pagination.parse(query,Set.of("region","direction","time","compatibleOnly"));String region=InputText.trim(query.get("region")),direction=query.get("direction"),time=query.get("time");
        ApiException.require((region==null || !region.isEmpty() && region.length()<=100) && (direction==null || Set.of("ida","volta").contains(direction)) && (time==null || time.matches("([01][0-9]|2[0-3]):[0-5][0-9]")),"VALIDATION_ERROR","Confira os campos informados.",422);
        return new Search(region,direction,time,RideQueries.bool(query,"compatibleOnly"),page);
    }
}
