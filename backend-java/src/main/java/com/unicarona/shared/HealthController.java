package com.unicarona.shared;

import com.unicarona.config.LegacyMigrationManifest;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate database;
    private final LegacyMigrationManifest manifest;
    private final Flyway flyway;
    private final com.unicarona.privacy.service.PrivacyReadiness privacy;
    public HealthController(JdbcTemplate database, LegacyMigrationManifest manifest, Flyway flyway,com.unicarona.privacy.service.PrivacyReadiness privacy) {
        this.database = database; this.manifest = manifest; this.flyway = flyway;this.privacy=privacy;
    }
    public record Status(String status) {}
    @GetMapping("/health")
    @Operation(summary = "Verificar se o processo responde")
    public Status health() { return new Status("ok"); }

    @GetMapping("/health/ready")
    @Operation(summary = "Verificar banco, migrations e checksums")
    public ResponseEntity<Status> ready() {
        try {
            Map<String, String> applied = database.query("SELECT version,checksum FROM schema_migrations", result -> {
                Map<String, String> values = new java.util.HashMap<>();
                while (result.next()) values.put(result.getString(1), result.getString(2));
                return values;
            });
            if (privacy.ready() && manifest.entries().stream().allMatch(entry -> entry.checksum().equals(applied.get(entry.legacyVersion())))
                    && flyway.validateWithResult().validationSuccessful) return ResponseEntity.ok(new Status("ready"));
        } catch (Exception ignored) { /* No internal diagnostics in public health response. */ }
        return ResponseEntity.status(503).body(new Status("not_ready"));
    }
}
