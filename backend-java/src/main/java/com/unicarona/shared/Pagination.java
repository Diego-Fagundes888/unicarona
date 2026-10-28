package com.unicarona.shared;

import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public record Pagination(int limit,int offset) {
    public static Map<String,String> singleValues(MultiValueMap<String,String> query) {
        ApiException.require(query.values().stream().allMatch(values -> values.size()==1),"VALIDATION_ERROR","Confira os campos informados.",422);
        return query.toSingleValueMap();
    }
    public static Pagination parse(Map<String,String> query,Set<String> extra) {
        ApiException.require(query.keySet().stream().allMatch(k -> k.equals("limit") || k.equals("offset") || extra.contains(k)),"VALIDATION_ERROR","Confira os campos informados.",422);
        String limit=query.getOrDefault("limit","30"),offset=query.getOrDefault("offset","0");
        ApiException.require(limit.matches("\\d{1,3}") && offset.matches("\\d{1,4}"),"VALIDATION_ERROR","Confira os campos informados.",422);
        int l=Integer.parseInt(limit),o=Integer.parseInt(offset);
        ApiException.require(l>=1 && l<=100 && o<=5000,"VALIDATION_ERROR","Confira os campos informados.",422);
        return new Pagination(l,o);
    }
}
