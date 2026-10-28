package com.unicarona.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptoServiceTest {
    private final CryptoService crypto = new CryptoService();
    @Test void argon2idUsesExistingParametersAndRejectsWrongPassword() {
        String hash = crypto.hashPassword("Synthetic-password-2026");
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(crypto.verifyPassword(hash, "Synthetic-password-2026"));
        assertFalse(crypto.verifyPassword(hash, "Different-password"));
        assertFalse(crypto.verifyPassword("broken", "Synthetic-password-2026"));
    }
    @Test void authenticatedEncryptionAndTokenFormat() {
        String key = "ab".repeat(32);
        String encrypted = crypto.encrypt("PIN 0012 — teste", key);
        assertEquals("PIN 0012 — teste", crypto.decrypt(encrypted, key));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(encrypted, "cd".repeat(32)));
        String token = crypto.randomToken();
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(token, crypto.randomToken());
        assertTrue(crypto.equalToken(crypto.hmac(token, "fixture"), crypto.hmac(token, "fixture")));
        assertFalse(crypto.equalToken("1", "12"));
    }
    @Test void nodeHashesAndCiphertextsRemainUsableInJava() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/node-crypto-fixture.json")) {
            var fixture = mapper.readTree(input);
            assertTrue(crypto.verifyPassword(fixture.get("passwordHash").asText(), fixture.get("password").asText()));
            assertEquals(fixture.get("text").asText(), crypto.decrypt(fixture.get("ciphertext").asText(), fixture.get("key").asText()));
            assertEquals(fixture.get("tokenHash").asText(), crypto.hmac(fixture.get("token").asText(), fixture.get("pepper").asText()));
            var javaFixture = java.util.Map.of(
                "passwordHash", crypto.hashPassword(fixture.get("password").asText()),
                "password", fixture.get("password").asText(),
                "key", fixture.get("key").asText(),
                "text", fixture.get("text").asText(),
                "ciphertext", crypto.encrypt(fixture.get("text").asText(), fixture.get("key").asText()));
            // Synthetic data only, for the reverse Node verification; never user credentials.
            mapper.writeValue(java.nio.file.Path.of("target/java-crypto-fixture.json").toFile(), javaFixture);
        }
    }
}
