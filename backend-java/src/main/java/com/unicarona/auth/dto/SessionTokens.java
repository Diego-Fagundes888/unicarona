package com.unicarona.auth.dto;

import java.util.UUID;

/** Internal value: never serialized as an API response or logged. */
public record SessionTokens(String access, String refresh, String csrf, UUID sessionId, int accessMinutes, int refreshDays) {
    @Override public String toString() { return "SessionTokens[redacted]"; }
}
