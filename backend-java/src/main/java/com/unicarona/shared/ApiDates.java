package com.unicarona.shared;

import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;

public final class ApiDates {
    private ApiDates() {}
    public static String timestamp(Instant value) {
        return value==null?null:new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }
}
