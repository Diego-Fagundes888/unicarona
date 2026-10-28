package com.unicarona.reservation.dto;

import com.unicarona.shared.*;
import java.util.*;

public record RequestQueries(String side,String status,Pagination page) {
    public static RequestQueries parse(Map<String,String> query) {
        var page=Pagination.parse(query,Set.of("side","status"));String side=query.getOrDefault("side","passenger"),status=query.get("status");
        ApiException.require(Set.of("passenger","driver").contains(side) && (status==null || Set.of("pending","accepted","confirmed","rejected","cancelled","expired").contains(status)),"VALIDATION_ERROR","Confira os campos informados.",422);
        return new RequestQueries(side,status,page);
    }
}
