package com.unicarona.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class LegacyMigrationManifest {
    public record Entry(int version, String legacyVersion, String resource, String checksum) {}
    private final List<Entry> entries;

    public LegacyMigrationManifest() throws IOException {
        try (var input = new ClassPathResource("db/legacy-manifest.json").getInputStream()) {
            entries = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<Entry>>() {}));
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.version() != i + 1 || !sha256(sql(entry)).equals(entry.checksum())) {
                throw new IllegalStateException("Invalid migration manifest");
            }
        }
        if (entries.isEmpty()) throw new IllegalStateException("No migrations available");
    }

    public List<Entry> entries() { return entries; }
    public byte[] sql(Entry entry) throws IOException {
        try (var input = new ClassPathResource("db/migration/" + entry.resource()).getInputStream()) {
            return input.readAllBytes();
        }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
