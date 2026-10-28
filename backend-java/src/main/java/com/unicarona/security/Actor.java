package com.unicarona.security;

import java.util.Set;
import java.util.UUID;

public record Actor(UUID id, String role, String accountStatus, UUID sessionId, UUID familyId,
                    String csrfHash, boolean emailVerified, Set<String> authorities) {
    @Override public String toString() { return "Actor[redacted]"; }
}
