package com.unicarona.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/** Validates the old history before registering an explicit Flyway baseline. */
@Component
public class LegacyFlywayMigrationStrategy implements FlywayMigrationStrategy {
    private final DataSource dataSource;
    private final LegacyMigrationManifest manifest;

    public LegacyFlywayMigrationStrategy(DataSource dataSource, LegacyMigrationManifest manifest) {
        this.dataSource = dataSource;
        this.manifest = manifest;
    }

    @Override
    public void migrate(Flyway flyway) {
        try (Connection connection = dataSource.getConnection()) {
            // Same lock as the previous migration executor; also serializes Java instances.
            execute(connection, "SELECT pg_advisory_lock(hashtext('unicarona_migrations'))");
            try {
                int legacyVersion = validateLegacyHistory(connection);
                if (!tableExists(connection, "flyway_schema_history") && legacyVersion > 0) {
                    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                        .baselineVersion(String.valueOf(legacyVersion)).baselineDescription("Validated TypeScript migration history")
                        .cleanDisabled(true).load().baseline();
                }
                flyway.migrate();
                // Preserve the old readiness/history contract during the comparison period.
                execute(connection, "CREATE TABLE IF NOT EXISTS schema_migrations(version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations(version,checksum) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                    for (var entry : manifest.entries()) {
                        insert.setString(1, entry.legacyVersion()); insert.setString(2, entry.checksum()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                validateLegacyHistory(connection);
            } finally { execute(connection, "SELECT pg_advisory_unlock(hashtext('unicarona_migrations'))"); }
        } catch (SQLException e) {
            // Do not include driver errors that can contain credentials or SQL.
            throw new IllegalStateException("Database migration failed (SQL state " + e.getSQLState() + ")");
        }
    }

    public int validateLegacyHistory(Connection connection) throws SQLException {
        if (!tableExists(connection, "schema_migrations")) return 0;
        Map<String, String> history = new HashMap<>();
        try (var query = connection.createStatement(); var result = query.executeQuery("SELECT version,checksum FROM schema_migrations")) {
            while (result.next()) history.put(result.getString(1), result.getString(2));
        }
        int version = 0;
        boolean gap = false;
        for (var entry : manifest.entries()) {
            String checksum = history.remove(entry.legacyVersion());
            if (checksum == null) { gap = true; continue; }
            if (gap || !checksum.equals(entry.checksum())) throw new IllegalStateException("Legacy migration history is incomplete or changed");
            version = entry.version();
        }
        if (!history.isEmpty()) throw new IllegalStateException("Unknown legacy migrations; update the Java migration manifest first");
        return version;
    }
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            query.setString(1, "public." + table);
            try (var result = query.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var query = connection.createStatement()) { query.execute(sql); }
    }
}
