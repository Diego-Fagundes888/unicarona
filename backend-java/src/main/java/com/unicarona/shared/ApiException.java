package com.unicarona.shared;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final String code;
    private final int status;
    private final Map<String, Object> details;
    public ApiException(String code, String message, int status) { this(code, message, status, Map.of()); }
    public ApiException(String code, String message, int status, Map<String, Object> details) {
        super(message); this.code = code; this.status = status; this.details = Map.copyOf(details);
    }
    public String code() { return code; }
    public int status() { return status; }
    public Map<String, Object> details() { return details; }
    public static void require(boolean condition, String code, String message, int status) {
        if (!condition) throw new ApiException(code, message, status);
    }
}
