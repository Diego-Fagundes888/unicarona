package com.unicarona.ride.dto;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public final class RideQueries {
    private RideQueries() {}
    public record Search(String region,String direction,String date,String time,Integer maxPrice,boolean automatic,boolean onlyAvailable,String driverId,Pagination page) {}
    public record Mine(Pagination page,boolean upcoming) {}
    public static boolean validDate(String value) {
        if(value==null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
        try { return LocalDate.parse(value).toString().equals(value); } catch(DateTimeParseException error) { return false; }
    }
    public static boolean bool(Map<String,String> query,String name) {
        String value=query.get(name);valid(value==null || Set.of("true","false").contains(value));return "true".equals(value);
    }
    public static Search search(Map<String,String> query) {
        var page=Pagination.parse(query,Set.of("region","direction","date","time","maxPrice","automatic","onlyAvailable","driverId"));
        String region=InputText.trim(query.get("region")),direction=query.getOrDefault("direction","ida"),date=query.getOrDefault("date",LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString()),time=query.get("time"),price=query.get("maxPrice"),driver=query.get("driverId");
        valid(region==null || !region.isEmpty() && region.length()<=100);valid(Set.of("ida","volta").contains(direction));valid(validDate(date));
        valid(time==null || time.matches("([01][0-9]|2[0-3]):[0-5][0-9]"));valid(price==null || price.matches("\\d{1,9}"));valid(driver==null || driver.matches(AuthDto.UUID_PATTERN));
        return new Search(region,direction,date,time,price==null?null:Integer.valueOf(price),bool(query,"automatic"),bool(query,"onlyAvailable"),driver,page);
    }
    public static Mine mine(Map<String,String> query) { return new Mine(Pagination.parse(query,Set.of("upcoming")),bool(query,"upcoming")); }
    private static void valid(boolean value) { ApiException.require(value,"VALIDATION_ERROR","Confira os campos informados.",422); }
}
