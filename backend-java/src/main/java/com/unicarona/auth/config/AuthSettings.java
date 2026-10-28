package com.unicarona.auth.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Secrets come only from configuration; deliberately has no secret-valued toString. */
@Component
public final class AuthSettings {
    private final String pepper, encryptionKey, jwtSecret, origin;
    private final int accessMinutes, refreshDays;
    private final boolean secure;
    public AuthSettings(Environment env) {
        pepper = required(env, "TOKEN_PEPPER"); encryptionKey = required(env, "PIN_ENCRYPTION_KEY");
        jwtSecret = required(env, "JWT_SECRET"); origin = env.getProperty("APP_ORIGIN", "http://127.0.0.1:3000");
        secure = "production".equals(env.getProperty("NODE_ENV"));
        accessMinutes = env.getProperty("SESSION_ACCESS_MINUTES", Integer.class, 15);
        refreshDays = env.getProperty("SESSION_REFRESH_DAYS", Integer.class, 30);
        if (pepper.length() < 32 || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32
            || !encryptionKey.matches("[a-fA-F0-9]{64}") || accessMinutes < 1 || accessMinutes > 60 || refreshDays < 1 || refreshDays > 90)
            throw new IllegalStateException("Invalid authentication configuration; check secret lengths and session durations.");
        URI uri = URI.create(origin);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath().isEmpty()) || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
            || (secure && !"https".equals(uri.getScheme()))) throw new IllegalStateException("APP_ORIGIN must be an HTTP origin; production requires HTTPS.");
    }
    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing authentication setting: " + key);
        return value;
    }
    public String pepper() { return pepper; }
    public String encryptionKey() { return encryptionKey; }
    public String jwtSecret() { return jwtSecret; }
    public String origin() { return origin; }
    public int accessMinutes() { return accessMinutes; }
    public int refreshDays() { return refreshDays; }
    public boolean secure() { return secure; }
}
