package com.unicarona.privacy.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.privacy.config.*;
import com.unicarona.privacy.service.*;
import com.unicarona.security.CryptoService;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.StandardEnvironment;

/**
 * File-only operator command: no Spring application, datasource, migrations or provider connection.
 */
public final class PrivacyCopiesCommand {
  private PrivacyCopiesCommand() {}

  public static void main(String[] arguments) {
    var json = new ObjectMapper().registerModule(new JavaTimeModule());
    int code = 0;
    try {
      System.out.println(json.writeValueAsString(execute(arguments, json)));
    } catch (Exception failure) {
      code = 1;
      String message = failure.getMessage();
      boolean safe =
          (failure.getClass() == IOException.class
                  || failure.getClass() == IllegalStateException.class)
              && message != null
              && message.matches("[A-Za-z0-9 .;:_-]{1,180}");
      try {
        System.out.println(
            json.writeValueAsString(
                Map.of(
                    "ok",
                    false,
                    "code",
                    "PRIVATE_COPIES_OPERATION_FAILED",
                    "reason",
                    safe
                        ? message
                        : "Private copy operation failed; inspect operator configuration without exposing credentials.")));
      } catch (Exception ignored) {
      }
    }
    if (code != 0) System.exit(code);
  }

  static Map<String, Object> execute(String[] arguments, ObjectMapper json) throws Exception {
    if (arguments.length == 0) throw new IOException("Specify a private copy operation.");
    String action = arguments[0];
    var options = new HashMap<String, String>();
    for (int n = 1; n < arguments.length; n += 2) {
      if (n + 1 >= arguments.length
          || !arguments[n].startsWith("--")
          || options.put(arguments[n].substring(2), arguments[n + 1]) != null)
        throw new IOException("Invalid operator command arguments.");
    }
    if (!Set.of("initialize", "import-legacy", "status", "prune", "rekey", "reserve", "finalize")
            .contains(action)
        || !Set.of("batch", "created-at", "name", "id", "restore-point")
            .containsAll(options.keySet()))
      throw new IOException("Unsupported private copy operation.");
    var env = new StandardEnvironment();
    var crypto = new CryptoService();
    var files = new StorageSettings(env);
    var settings = new PrivacyOperationsSettings(env, files);
    if (!settings.enabled())
      throw new IOException("Configure the independent private operations root first.");
    var keys = PrivacySigningKeys.forOperations(env, crypto, json);
    var catalog =
        new PrivacyBackupCatalog(settings, new PrivacySettings(env), keys, json, Clock.systemUTC());
    var journal =
        PrivacyErasureJournal.forOperations(
            files, env, json, crypto, keys, catalog, Clock.systemUTC());
    int batch = Integer.parseInt(options.getOrDefault("batch", "100"));
    if (batch < 1 || batch > 10000) throw new IOException("Invalid operator cleanup batch.");
    switch (action) {
      case "initialize" -> {
        catalog.initialize();
        journal.entries();
        return Map.of("ok", true, "initialized", true);
      }
      case "import-legacy" -> {
        Instant created =
            options.containsKey("created-at")
                ? Instant.parse(options.get("created-at"))
                : legacyCapture(settings.root(), json);
        int count = 0;
        for (String type : List.of("backups", "archives")) {
          Path container = settings.root().resolve(type);
          PrivacyOperationFiles.createPrivateDirectories(container);
          List<Path> children;
          try (var listing = Files.list(container)) {
            children = listing.toList();
          }
          for (Path child : children) {
            String name = child.getFileName().toString();
            catalog.locked(() -> catalog.register(type, name, created, Instant.EPOCH, true));
            count++;
          }
        }
        return Map.of(
            "ok", true, "registeredHistoricalResources", count, "deadlinesExtended", false);
      }
      case "reserve" -> {
        String name = required(options, "name");
        if (!UUID.fromString(name).toString().equals(name))
          throw new IOException("New snapshots require a random UUID resource name.");
        var reserved =
            catalog.locked(
                () -> {
                  Path container = settings.root().resolve("backups");
                  PrivacyOperationFiles.createPrivateDirectories(container);
                  Path target = PrivacyOperationFiles.child(container, name);
                  if (PrivacyOperationFiles.existsVerified(target, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("New snapshot destination must be absent.");
                  Files.createDirectory(target);
                  return catalog.reserve("backups", name);
                });
        return Map.of(
            "ok",
            true,
            "recordId",
            reserved.id(),
            "resourceName",
            name,
            "directory",
            settings.root().resolve("backups").resolve(name).toString());
      }
      case "finalize" -> {
        UUID id = UUID.fromString(required(options, "id"));
        Instant point = Instant.parse(required(options, "restore-point"));
        catalog.locked(() -> catalog.finalizeSnapshot(id, point));
        return Map.of("ok", true, "finalized", true);
      }
      case "prune" -> {
        journal.entries();
        var result = catalog.locked(() -> catalog.prune(batch));
        return Map.of(
            "ok",
            true,
            "deletedPaths",
            result.deletedPaths(),
            "completedCopies",
            result.completedCopies(),
            "expiredJournalEntries",
            0,
            "suppressionExpiryRequiresDatabaseWorker",
            true);
      }
      case "rekey" -> {
        journal.entries();
        int count = catalog.locked(() -> catalog.rekey(batch));
        count += journal.rekey(batch);
        return Map.of("ok", true, "rekeyedRecords", count);
      }
      default -> {
        var entries = journal.entries();
        var fileEntries = journal.fileEntries();
        var orphans = journal.orphanEntries();
        var inventory = catalog.locked(catalog::inventory);
        return Map.of(
            "ok",
            true,
            "registeredCopies",
            inventory.copies().size(),
            "presentCopies",
            inventory.present(),
            "journalEntries",
            entries.size(),
            "fileJournalEntries",
            fileEntries.size(),
            "orphanJournalEntries",
            orphans.size(),
            "orphanCompletionPending",
            orphans.stream().filter(e -> e.confirmedAbsentAt() == null).count(),
            "datasetBound",
            inventory.store().datasetId() != null,
            "minimumRestorePointEpochMs",
            inventory.store().minimumRestorePointEpochMs(),
            "pendingJournalOperation",
            inventory.store().pending() != null);
      }
    }
  }

  private static String required(Map<String, String> options, String key) throws IOException {
    String value = options.get(key);
    if (value == null || value.isBlank())
      throw new IOException("Required operator argument is missing.");
    return value;
  }

  private static Instant legacyCapture(Path root, ObjectMapper json) throws IOException {
    Path proof = root.resolve("archives/migration/before.json");
    if (PrivacyOperationFiles.existsVerified(proof)) {
      var value = json.readTree(PrivacyOperationFiles.read(proof, 65536));
      try {
        return Instant.parse(value.path("at").asText());
      } catch (Exception invalid) {
        throw new IOException("Legacy snapshot capture proof is invalid.");
      }
    }
    throw new IOException("Supply the original capture time for historical copies.");
  }
}
