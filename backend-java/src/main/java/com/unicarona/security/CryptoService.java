package com.unicarona.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CryptoService {
    private final SecureRandom random = new SecureRandom();
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    public String randomToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String hashPassword(String password) { return passwords.encode(password); }
    public boolean verifyPassword(String encoded, String password) {
        if (encoded == null) return false;
        var matcher = java.util.regex.Pattern.compile("^\\$argon2id\\$v=19\\$((?:[mtp]=\\d+,){2}[mtp]=\\d+)\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)$").matcher(encoded);
        if (!matcher.matches()) return false;
        var parameters = new java.util.HashMap<String, String>();
        for (String parameter : matcher.group(1).split(",")) {
            String[] parts = parameter.split("=", 2);
            if (parameters.put(parts[0], parts[1]) != null) return false;
        }
        if (!parameters.keySet().equals(java.util.Set.of("m", "t", "p"))) return false;
        // Node 0.45 emits m,p,t; Spring decodes m,t,p. This does not rewrite the
        // stored hash or change its salt, parameters, algorithm or digest.
        String normalized = "$argon2id$v=19$m=" + parameters.get("m") + ",t=" + parameters.get("t") + ",p=" + parameters.get("p")
            + "$" + matcher.group(2) + "$" + matcher.group(3);
        try { return passwords.matches(password, normalized); }
        catch (RuntimeException ignored) { return false; }
    }
    public String hmac(String token, String pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("HMAC unavailable", e); }
    }
    public boolean equalToken(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    /** Wire format shared with Node: IV(12), tag(16), ciphertext. */
    public String encrypt(String text, String hexadecimalKey) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, hexadecimalKey, iv);
            byte[] result = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] wire = new byte[iv.length + result.length];
            System.arraycopy(iv, 0, wire, 0, 12);
            System.arraycopy(result, result.length - 16, wire, 12, 16);
            System.arraycopy(result, 0, wire, 28, result.length - 16);
            return Base64.getEncoder().encodeToString(wire);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Encryption failed", e); }
    }
    public String decrypt(String encoded, String hexadecimalKey) {
        try {
            byte[] wire = Base64.getDecoder().decode(encoded);
            if (wire.length < 28) throw new IllegalArgumentException("Invalid ciphertext");
            byte[] data = new byte[wire.length - 12];
            System.arraycopy(wire, 28, data, 0, wire.length - 28);
            System.arraycopy(wire, 12, data, wire.length - 28, 16);
            return new String(cipher(Cipher.DECRYPT_MODE, hexadecimalKey, Arrays.copyOf(wire, 12)).doFinal(data), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("Decryption failed"); }
    }
    private Cipher cipher(int mode, String key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(HexFormat.of().parseHex(key), "AES"), new GCMParameterSpec(128, iv));
        return cipher;
    }
}
