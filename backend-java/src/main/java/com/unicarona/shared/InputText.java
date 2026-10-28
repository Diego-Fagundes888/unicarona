package com.unicarona.shared;

import java.util.Locale;

public final class InputText {
    private static final String WHITESPACE="\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    private InputText() {}
    public static String trim(String value) { return value==null?null:value.replaceAll("^["+WHITESPACE+"]+|["+WHITESPACE+"]+$",""); }
    public static String phone(String value) {
        if(value==null) return null;
        ApiException.require(value.length()<=25,"VALIDATION_ERROR","Confira os campos informados.",422);
        String digits=value.replaceAll("\\D","");
        ApiException.require(digits.isEmpty() || (digits.length()>=10 && digits.length()<=15),"VALIDATION_ERROR","Confira os campos informados.",422);
        return digits;
    }
    public static String plate(String value) { return value==null?null:value.toUpperCase(Locale.ROOT).replaceAll("[-"+WHITESPACE+"]",""); }
}
