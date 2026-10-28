package com.unicarona.admin.dto;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.ride.dto.RideQueries;
import com.unicarona.shared.*;
import java.util.*;

public final class AdminCatalogQueries {
    private AdminCatalogQueries() {}
    public record Pricing(UUID university,Pagination page) {}
    public record Calendar(UUID university,String start,String end,Pagination page) {}
    public static Pricing pricing(Map<String,String> query) { var page=Pagination.parse(query,Set.of("universityId"));return new Pricing(university(query),page); }
    public static Calendar calendar(Map<String,String> query) {
        var page=Pagination.parse(query,Set.of("universityId","startDate","endDate"));UUID university=university(query);String start=query.get("startDate"),end=query.get("endDate");valid(RideQueries.validDate(start) && RideQueries.validDate(end));return new Calendar(university,start,end,page);
    }
    private static UUID university(Map<String,String> query) { String value=query.get("universityId");valid(value!=null && value.matches(AuthDto.UUID_PATTERN));return UUID.fromString(value); }
    private static void valid(boolean value) { ApiException.require(value,"VALIDATION_ERROR","Confira os campos informados.",422); }
}
