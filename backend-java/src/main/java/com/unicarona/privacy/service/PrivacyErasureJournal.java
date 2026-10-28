package com.unicarona.privacy.service;

import static com.unicarona.privacy.config.PrivacySigningKeys.Purpose.JOURNAL;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.file.config.StorageSettings;
import com.unicarona.file.storage.PrivateFileStorage;
import com.unicarona.privacy.config.PrivacySigningKeys;
import com.unicarona.security.CryptoService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Independent suppression evidence, including expiry after physical backup cleanup. */
@Component
public class PrivacyErasureJournal {
  public record Entry(
      int version,
      UUID owner,
      UUID closure,
      Instant preparedAt,
      Instant suppressionUntil,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
          Instant confirmedAt,
      String keyId,
      String signature) {
    public Entry(
        int version,
        UUID owner,
        UUID closure,
        Instant preparedAt,
        Instant until,
        String keyId,
        String signature) {
      this(version, owner, closure, preparedAt, until, null, keyId, signature);
    }

    @Override
    public String toString() {
      return "ErasureJournalEntry[redacted]";
    }
  }

  public record FileEntry(
      int version,
      UUID owner,
      UUID file,
      Instant preparedAt,
      Instant suppressionUntil,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
          Instant confirmedAt,
      String keyId,
      String signature) {
    public FileEntry(
        int version,
        UUID owner,
        UUID file,
        Instant preparedAt,
        Instant until,
        String keyId,
        String signature) {
      this(version, owner, file, preparedAt, until, null, keyId, signature);
    }

    @Override
    public String toString() {
      return "FileErasureJournalEntry[redacted]";
    }
  }

  public record OrphanEntry(
      int version,
      UUID dataset,
      UUID file,
      int bytes,
      String sha256,
      Instant preparedAt,
      Instant suppressionUntil,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
          Instant confirmedAbsentAt,
      String keyId,
      String signature) {
    @Override
    public String toString() {
      return "OrphanFileErasureEntry[redacted]";
    }
  }

  private final Path root;
  private final ObjectMapper json;
  private final CryptoService crypto;
  private final String legacyPepper;
  private final PrivacySigningKeys keys;
  private final PrivacyBackupCatalog catalog;
  private final Clock clock;
  private final PrivateFileStorage activeFiles;
  private Retirement retirementAfter;

  @org.springframework.beans.factory.annotation.Autowired
  public PrivacyErasureJournal(
      StorageSettings files,
      Environment env,
      ObjectMapper json,
      CryptoService crypto,
      AuthSettings settings,
      PrivacySigningKeys keys,
      PrivacyBackupCatalog catalog,
      Clock clock) {
    this(files, env, json, crypto, settings.pepper(), keys, catalog, clock);
  }

  public static PrivacyErasureJournal forOperations(
      StorageSettings files,
      Environment env,
      ObjectMapper json,
      CryptoService crypto,
      PrivacySigningKeys keys,
      PrivacyBackupCatalog catalog,
      Clock clock) {
    return new PrivacyErasureJournal(
        files, env, json, crypto, env.getProperty("TOKEN_PEPPER", ""), keys, catalog, clock);
  }

  private PrivacyErasureJournal(
      StorageSettings files,
      Environment env,
      ObjectMapper json,
      CryptoService crypto,
      String legacyPepper,
      PrivacySigningKeys keys,
      PrivacyBackupCatalog catalog,
      Clock clock) {
    String configured = env.getProperty("PRIVACY_ERASURE_JOURNAL_PATH", "").strip();
    root =
        Path.of(
                configured.isEmpty()
                    ? files
                        .root()
                        .resolveSibling(files.root().getFileName() + "-privacy-journal")
                        .toString()
                    : configured)
            .toAbsolutePath()
            .normalize();
    if (root.startsWith(files.root())
        || files.root().startsWith(root)
        || root.startsWith(files.publicRoot()))
      throw new IllegalStateException(
          "Erasure journal must be outside active uploads and public files.");
    this.json = json;
    this.crypto = crypto;
    this.legacyPepper = legacyPepper;
    this.keys = keys;
    this.catalog = catalog;
    this.clock = clock;
    activeFiles = new PrivateFileStorage(files);
  }

  private String witness(Instant confirmed) {
    return confirmed == null ? "pending" : confirmed.toString();
  }

  private String body(Entry e) {
    String core = e.owner() + "|" + e.closure() + "|" + e.preparedAt() + "|" + e.suppressionUntil();
    return e.version() == 3
        ? "privacy-erasure-journal-v3|" + core + "|" + witness(e.confirmedAt())
        : "privacy-erasure-journal-v2|" + core;
  }

  private Entry signed(
      UUID owner, UUID closure, Instant prepared, Instant until, Instant confirmed) {
    String id = keys.current(JOURNAL);
    var e = new Entry(3, owner, closure, prepared, until, confirmed, id, "");
    return new Entry(
        3, owner, closure, prepared, until, confirmed, id, keys.sign(JOURNAL, id, body(e)));
  }

  private String body(FileEntry e) {
    String core = e.owner() + "|" + e.file() + "|" + e.preparedAt() + "|" + e.suppressionUntil();
    return e.version() == 2
        ? "privacy-file-erasure-v2|" + core + "|" + witness(e.confirmedAt())
        : "privacy-file-erasure-v1|" + core;
  }

  private FileEntry signedFile(
      UUID owner, UUID file, Instant prepared, Instant until, Instant confirmed) {
    String id = keys.current(JOURNAL);
    var e = new FileEntry(2, owner, file, prepared, until, confirmed, id, "");
    return new FileEntry(
        2, owner, file, prepared, until, confirmed, id, keys.sign(JOURNAL, id, body(e)));
  }

  private <T> T decode(byte[] bytes, Class<T> type) throws IOException {
    try (var parser = json.createParser(bytes)) {
      parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      T value =
          json.readerFor(type)
              .with(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .with(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
              .readValue(parser);
      if (parser.nextToken() != null) throw new IOException("Trailing suppression record data.");
      return value;
    } catch (IOException invalid) {
      throw new IOException("Suppression record cannot be decoded.");
    }
  }

  private FileEntry parseFile(String name, byte[] bytes) throws IOException {
    if (bytes.length > 4096) throw new IOException("Invalid file erasure journal size.");
    var e = decode(bytes, FileEntry.class);
    if (!Set.of(1, 2).contains(e.version())
        || e.owner() == null
        || e.file() == null
        || e.preparedAt() == null
        || e.suppressionUntil() == null
        || !("file-" + e.file()).equals(name)
        || !e.suppressionUntil().isAfter(e.preparedAt())
        || (e.confirmedAt() != null
            && (e.version() == 1 || e.confirmedAt().isBefore(e.preparedAt())))
        || !keys.verify(JOURNAL, e.keyId(), body(e), e.signature()))
      throw new IOException("File erasure journal integrity check failed.");
    return e;
  }

  private String body(OrphanEntry e) {
    return "privacy-orphan-file-erasure-v1|"
        + e.dataset()
        + "|"
        + e.file()
        + "|"
        + e.bytes()
        + "|"
        + e.sha256()
        + "|"
        + e.preparedAt()
        + "|"
        + e.suppressionUntil()
        + "|"
        + (e.confirmedAbsentAt() == null ? "pending" : e.confirmedAbsentAt());
  }

  private OrphanEntry signedOrphan(
      UUID dataset,
      UUID file,
      int size,
      String sha,
      Instant prepared,
      Instant until,
      Instant confirmed) {
    String key = keys.current(JOURNAL);
    var e = new OrphanEntry(1, dataset, file, size, sha, prepared, until, confirmed, key, "");
    return new OrphanEntry(
        1,
        dataset,
        file,
        size,
        sha,
        prepared,
        until,
        confirmed,
        key,
        keys.sign(JOURNAL, key, body(e)));
  }

  private OrphanEntry parseOrphan(String name, byte[] bytes) throws IOException {
    if (bytes.length > 4096 || !catalog.enabled())
      throw new IOException("Orphan suppression requires its independent dataset control.");
    OrphanEntry e;
    try (var parser = json.createParser(bytes)) {
      parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      e =
          json.readerFor(OrphanEntry.class)
              .with(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .with(
                  com.fasterxml.jackson.databind.DeserializationFeature
                      .FAIL_ON_MISSING_CREATOR_PROPERTIES)
              .with(
                  com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
              .readValue(parser);
      if (parser.nextToken() != null) throw new IOException("Trailing orphan suppression data.");
    }
    if (e.version() != 1
        || e.dataset() == null
        || !e.dataset().equals(catalog.store().datasetId())
        || e.file() == null
        || !("orphan-" + e.file()).equals(name)
        || e.bytes() < 0
        || e.bytes() > 5 * 1024 * 1024
        || e.sha256() == null
        || !e.sha256().matches("[a-f0-9]{64}")
        || e.preparedAt() == null
        || e.suppressionUntil() == null
        || !e.suppressionUntil().isAfter(e.preparedAt())
        || (e.confirmedAbsentAt() != null && e.confirmedAbsentAt().isBefore(e.preparedAt()))
        || !keys.verify(JOURNAL, e.keyId(), body(e), e.signature()))
      throw new IOException("Orphan suppression integrity check failed.");
    return e;
  }

  private void validate(String name, byte[] bytes) throws IOException {
    if (name.startsWith("file-")) parseFile(name, bytes);
    else if (name.startsWith("orphan-")) parseOrphan(name, bytes);
    else parse(name, bytes);
  }

  private Entry parse(String name, byte[] bytes) throws IOException {
    if (bytes.length > 4096) throw new IOException("Invalid erasure journal size.");
    var e = decode(bytes, Entry.class);
    if (e.owner() == null
        || e.closure() == null
        || e.preparedAt() == null
        || e.suppressionUntil() == null
        || !e.closure().toString().equals(name)
        || !e.suppressionUntil().isAfter(e.preparedAt()))
      throw new IOException("Erasure journal identity check failed.");
    if (e.version() == 1 && legacyPepper.length() < 32)
      throw new IOException(
          "Legacy journal verification requires its retained authentication pepper.");
    if (e.confirmedAt() != null && (e.version() != 3 || e.confirmedAt().isBefore(e.preparedAt())))
      throw new IOException("Legacy suppression cannot claim an unsigned confirmation.");
    boolean valid =
        e.version() == 1
            ? crypto.equalToken(
                e.signature(),
                crypto.hmac(
                    "privacy-erasure-journal-v1|"
                        + e.owner()
                        + "|"
                        + e.closure()
                        + "|"
                        + e.preparedAt()
                        + "|"
                        + e.suppressionUntil(),
                    legacyPepper))
            : Set.of(2, 3).contains(e.version())
                && keys.verify(JOURNAL, e.keyId(), body(e), e.signature());
    if (!valid) throw new IOException("Erasure journal integrity check failed.");
    return e;
  }

  private SortedMap<String, byte[]> raw() throws IOException {
    var values = new TreeMap<String, byte[]>();
    if (!PrivacyOperationFiles.existsVerified(root, LinkOption.NOFOLLOW_LINKS)) return values;
    PrivacyOperationFiles.ordinary(root);
    List<Path> names;
    try (var files = Files.list(root)) {
      names = files.toList();
    }
    if (names.size() > 100000) throw new IOException("Erasure journal exceeds its supported size.");
    for (Path file : names) {
      if (PrivacyOperationFiles.interruptedTemporary(file)) continue;
      String name = file.getFileName().toString();
      if (!name.matches("(?:(?:file|orphan)-)?[a-f0-9-]{36}"))
        throw new IOException("Unexpected erasure journal entry.");
      byte[] bytes = PrivacyOperationFiles.read(file, 4096);
      validate(name, bytes);
      values.put(name, bytes);
    }
    return values;
  }

  private String digest(Map<String, byte[]> values) {
    var text = new StringBuilder();
    new TreeMap<>(values)
        .forEach(
            (name, bytes) ->
                text.append(name)
                    .append(':')
                    .append(PrivacyBackupCatalog.sha(new String(bytes, StandardCharsets.UTF_8)))
                    .append('\n'));
    return PrivacyBackupCatalog.sha(text.toString());
  }

  private void assertIndex(Map<String, byte[]> values) throws IOException {
    if (!digest(values).equals(catalog.store().journalDigest()))
      throw new IOException("Independent suppression journal is incomplete or was rolled back.");
  }

  private void recover() throws IOException {
    var pending = catalog.store().pending();
    if (pending == null) return;
    if (!Set.of("put", "remove").contains(pending.operation())
        || !pending.name().matches("(?:(?:file|orphan)-)?[a-f0-9-]{36}")
        || pending.payload() == null
        || pending.payload().length() > 4096)
      throw new IOException("Invalid pending suppression operation.");
    var actual = raw();
    String observed = digest(actual);
    if (observed.equals(pending.beforeDigest())) {
      PrivacyOperationFiles.createPrivateDirectories(root);
      Path target = PrivacyOperationFiles.child(root, pending.name());
      if ("put".equals(pending.operation())) {
        byte[] bytes = pending.payload().getBytes(StandardCharsets.UTF_8);
        validate(pending.name(), bytes);
        PrivacyOperationFiles.atomicWrite(target, bytes);
      } else {
        if (!pending.payload().isEmpty())
          throw new IOException("Invalid suppression retirement payload.");
        Files.deleteIfExists(target);
      }
      observed = digest(raw());
    }
    if (!observed.equals(pending.afterDigest()))
      throw new IOException("Pending suppression operation conflicts with independent evidence.");
    catalog.completeJournalChange();
    assertIndex(raw());
  }

  private void change(SortedMap<String, byte[]> before, String name, byte[] after, Instant floor)
      throws IOException {
    var changed = new TreeMap<>(before);
    if (after == null) changed.remove(name);
    else changed.put(name, after);
    var pending =
        new PrivacyBackupCatalog.PendingJournal(
            after == null ? "remove" : "put",
            name,
            digest(before),
            digest(changed),
            after == null ? "" : new String(after, StandardCharsets.UTF_8));
    catalog.beginJournalChange(pending, floor);
    recover();
  }

  public void ensure(UUID owner, UUID closure, Instant preparedAt, Instant until)
      throws IOException {
    var entry = signed(owner, closure, preparedAt, until, null);
    byte[] bytes = json.writeValueAsBytes(entry);
    parse(closure.toString(), bytes);
    if (catalog.enabled()) {
      catalog.locked(
          () -> {
            recover();
            var before = raw();
            assertIndex(before);
            put(before, entry, bytes, true);
            return null;
          });
    } else put(raw(), entry, bytes, false);
  }

  private void put(SortedMap<String, byte[]> before, Entry entry, byte[] bytes, boolean indexed)
      throws IOException {
    String name = entry.closure().toString();
    if (before.containsKey(name)) {
      var existing = parse(name, before.get(name));
      if (!existing.owner().equals(entry.owner())
          || !existing.preparedAt().equals(entry.preparedAt())
          || !existing.suppressionUntil().equals(entry.suppressionUntil()))
        throw new IOException("Erasure journal identity conflict.");
      return;
    }
    if (indexed) change(before, name, bytes, Instant.EPOCH);
    else {
      PrivacyOperationFiles.createPrivateDirectories(root);
      PrivacyOperationFiles.atomicWrite(PrivacyOperationFiles.child(root, name), bytes);
    }
  }

  public List<Entry> entries() throws IOException {
    if (catalog.enabled())
      return catalog.locked(
          () -> {
            recover();
            var values = raw();
            assertIndex(values);
            return parsed(values);
          });
    return parsed(raw());
  }

  /** First durable preparation wins; retries and rekeying never extend suppression deadlines. */
  public FileEntry ensureFile(UUID owner, UUID file, Instant prepared, Instant until)
      throws IOException {
    var entry = signedFile(owner, file, prepared, until, null);
    byte[] bytes = json.writeValueAsBytes(entry);
    String name = "file-" + file;
    parseFile(name, bytes);
    if (catalog.enabled())
      return catalog.locked(
          () -> {
            recover();
            var values = raw();
            assertIndex(values);
            return putFile(values, entry, bytes, true);
          });
    return putFile(raw(), entry, bytes, false);
  }

  private FileEntry putFile(
      SortedMap<String, byte[]> values, FileEntry entry, byte[] bytes, boolean indexed)
      throws IOException {
    String name = "file-" + entry.file();
    if (values.containsKey(name)) {
      var existing = parseFile(name, values.get(name));
      if (!existing.owner().equals(entry.owner()))
        throw new IOException("File erasure journal identity conflict.");
      return existing.confirmedAt() == null ? existing : reopenFile(values, existing, indexed);
    }
    if (indexed) change(values, name, bytes, Instant.EPOCH);
    else {
      PrivacyOperationFiles.createPrivateDirectories(root);
      PrivacyOperationFiles.atomicWrite(PrivacyOperationFiles.child(root, name), bytes);
    }
    return entry;
  }

  public List<FileEntry> fileEntries() throws IOException {
    if (catalog.enabled())
      return catalog.locked(
          () -> {
            recover();
            var values = raw();
            assertIndex(values);
            return parsedFiles(values);
          });
    return parsedFiles(raw());
  }

  @FunctionalInterface
  private interface Mutation<T> {
    T apply(SortedMap<String, byte[]> values, boolean indexed) throws IOException;
  }

  private <T> T mutate(Mutation<T> work) throws IOException {
    if (catalog.enabled())
      return catalog.locked(
          () -> {
            recover();
            var values = raw();
            assertIndex(values);
            return work.apply(values, true);
          });
    return work.apply(raw(), false);
  }

  private void persist(
      SortedMap<String, byte[]> values, String name, Object record, boolean indexed)
      throws IOException {
    byte[] encoded = json.writeValueAsBytes(record);
    validate(name, encoded);
    if (indexed) change(values, name, encoded, Instant.EPOCH);
    else {
      PrivacyOperationFiles.createPrivateDirectories(root);
      PrivacyOperationFiles.atomicWrite(PrivacyOperationFiles.child(root, name), encoded);
    }
  }

  private Instant confirmedAfter(Instant prepared) throws IOException {
    Instant confirmed = clock.instant();
    if (confirmed.isBefore(prepared))
      throw new IOException("Suppression confirmation clock precedes preparation.");
    return confirmed;
  }

  private Entry sameAccount(Map<String, byte[]> values, Entry expected) throws IOException {
    byte[] encoded = values.get(expected.closure().toString());
    if (encoded == null) throw new IOException("Missing account suppression instruction.");
    var actual = parse(expected.closure().toString(), encoded);
    if (!actual.owner().equals(expected.owner())
        || !actual.preparedAt().equals(expected.preparedAt())
        || !actual.suppressionUntil().equals(expected.suppressionUntil()))
      throw new IOException("Account confirmation does not match its instruction.");
    return actual;
  }

  private FileEntry sameFile(Map<String, byte[]> values, FileEntry expected) throws IOException {
    byte[] encoded = values.get("file-" + expected.file());
    if (encoded == null) throw new IOException("Missing file suppression instruction.");
    var actual = parseFile("file-" + expected.file(), encoded);
    if (!actual.owner().equals(expected.owner())
        || !actual.preparedAt().equals(expected.preparedAt())
        || !actual.suppressionUntil().equals(expected.suppressionUntil()))
      throw new IOException("File confirmation does not match its instruction.");
    return actual;
  }

  /** Internal coordinator API. Only a committed, verified database outcome may call this. */
  public Entry confirmAccount(Entry expected) throws IOException {
    return mutate(
        (values, indexed) -> {
          var actual = sameAccount(values, expected);
          if (actual.confirmedAt() != null) return actual;
          var next =
              signed(
                  actual.owner(),
                  actual.closure(),
                  actual.preparedAt(),
                  actual.suppressionUntil(),
                  confirmedAfter(actual.preparedAt()));
          persist(values, actual.closure().toString(), next, indexed);
          return next;
        });
  }

  public FileEntry confirmFileAbsent(FileEntry expected) throws IOException {
    return mutate(
        (values, indexed) -> {
          var actual = sameFile(values, expected);
          if (activeFiles.existsVerified(actual.file().toString()))
            throw new IOException(
                "Known file bytes are still present; suppression is not confirmed.");
          if (actual.confirmedAt() != null) return actual;
          var next =
              signedFile(
                  actual.owner(),
                  actual.file(),
                  actual.preparedAt(),
                  actual.suppressionUntil(),
                  confirmedAfter(actual.preparedAt()));
          persist(values, "file-" + actual.file(), next, indexed);
          return next;
        });
  }

  private Entry reopenAccount(SortedMap<String, byte[]> values, Entry actual, boolean indexed)
      throws IOException {
    if (actual.version() == 3 && actual.confirmedAt() == null) return actual;
    var next =
        signed(
            actual.owner(), actual.closure(), actual.preparedAt(), actual.suppressionUntil(), null);
    persist(values, actual.closure().toString(), next, indexed);
    return next;
  }

  private FileEntry reopenFile(SortedMap<String, byte[]> values, FileEntry actual, boolean indexed)
      throws IOException {
    if (actual.version() == 2 && actual.confirmedAt() == null) return actual;
    var next =
        signedFile(
            actual.owner(), actual.file(), actual.preparedAt(), actual.suppressionUntil(), null);
    persist(values, "file-" + actual.file(), next, indexed);
    return next;
  }

  public Entry reopenAccount(Entry expected) throws IOException {
    return mutate(
        (values, indexed) -> reopenAccount(values, sameAccount(values, expected), indexed));
  }

  public FileEntry reopenFile(FileEntry expected) throws IOException {
    return mutate((values, indexed) -> reopenFile(values, sameFile(values, expected), indexed));
  }

  /** No fabricated owner: an orphan instruction is scoped to dataset, UUID and exact content. */
  public OrphanEntry ensureOrphan(UUID file, int bytes, String sha, Instant prepared, Instant until)
      throws IOException {
    if (!catalog.enabled())
      throw new IOException("Orphan suppression requires independent operations.");
    return catalog.locked(
        () -> {
          recover();
          var values = raw();
          assertIndex(values);
          UUID dataset = catalog.store().datasetId();
          var entry = signedOrphan(dataset, file, bytes, sha, prepared, until, null);
          String name = "orphan-" + file;
          byte[] encoded = json.writeValueAsBytes(entry);
          parseOrphan(name, encoded);
          if (values.containsKey(name)) {
            var existing = parseOrphan(name, values.get(name));
            if (existing.bytes() != bytes || !existing.sha256().equals(sha))
              throw new IOException("Orphan suppression content conflict.");
            if (existing.confirmedAbsentAt() != null && activeFiles.observe(file).isPresent())
              return reopenOrphan(values, existing);
            return existing;
          }
          change(values, name, encoded, Instant.EPOCH);
          return entry;
        });
  }

  public List<OrphanEntry> orphanEntries() throws IOException {
    if (catalog.enabled())
      return catalog.locked(
          () -> {
            recover();
            var values = raw();
            assertIndex(values);
            return parsedOrphans(values);
          });
    return parsedOrphans(raw());
  }

  private OrphanEntry sameOrphan(Map<String, byte[]> values, OrphanEntry expected)
      throws IOException {
    byte[] bytes = values.get("orphan-" + expected.file());
    if (bytes == null) throw new IOException("Missing orphan suppression instruction.");
    var existing = parseOrphan("orphan-" + expected.file(), bytes);
    if (!existing.dataset().equals(expected.dataset())
        || existing.bytes() != expected.bytes()
        || !existing.sha256().equals(expected.sha256())
        || !existing.preparedAt().equals(expected.preparedAt())
        || !existing.suppressionUntil().equals(expected.suppressionUntil()))
      throw new IOException("Orphan confirmation does not match its instruction.");
    return existing;
  }

  /** Called after a committed database outcome. Pending intent alone never permits retirement. */
  public OrphanEntry confirmOrphanAbsent(OrphanEntry expected) throws IOException {
    if (!catalog.enabled())
      throw new IOException("Orphan confirmation requires its independent dataset.");
    return catalog.locked(
        () -> {
          recover();
          var values = raw();
          assertIndex(values);
          var existing = sameOrphan(values, expected);
          if (activeFiles.observe(existing.file()).isPresent())
            throw new IOException(
                "Orphan bytes are still present; suppression cannot be confirmed.");
          if (existing.confirmedAbsentAt() != null) return existing;
          Instant confirmed = clock.instant();
          if (confirmed.isBefore(existing.preparedAt()))
            throw new IOException("Orphan confirmation clock precedes its preparation.");
          var replacement =
              signedOrphan(
                  existing.dataset(),
                  existing.file(),
                  existing.bytes(),
                  existing.sha256(),
                  existing.preparedAt(),
                  existing.suppressionUntil(),
                  confirmed);
          change(
              values,
              "orphan-" + existing.file(),
              json.writeValueAsBytes(replacement),
              Instant.EPOCH);
          return replacement;
        });
  }

  private OrphanEntry reopenOrphan(SortedMap<String, byte[]> values, OrphanEntry existing)
      throws IOException {
    if (existing.confirmedAbsentAt() == null) return existing;
    var observed = activeFiles.observe(existing.file());
    if (observed.isEmpty()) return existing;
    if (observed.get().bytes() != existing.bytes()
        || !observed.get().sha256().equals(existing.sha256()))
      throw new IOException(
          "Reintroduced orphan bytes conflict with their suppression instruction.");
    var replacement =
        signedOrphan(
            existing.dataset(),
            existing.file(),
            existing.bytes(),
            existing.sha256(),
            existing.preparedAt(),
            existing.suppressionUntil(),
            null);
    change(values, "orphan-" + existing.file(), json.writeValueAsBytes(replacement), Instant.EPOCH);
    return replacement;
  }

  /** A restored physical copy invalidates the old absence witness before another erasure. */
  public OrphanEntry reopenOrphan(OrphanEntry expected) throws IOException {
    if (!catalog.enabled())
      throw new IOException("Orphan recovery requires its independent dataset.");
    return catalog.locked(
        () -> {
          recover();
          var values = raw();
          assertIndex(values);
          return reopenOrphan(values, sameOrphan(values, expected));
        });
  }

  private List<OrphanEntry> parsedOrphans(Map<String, byte[]> values) throws IOException {
    var entries = new ArrayList<OrphanEntry>();
    for (var value : values.entrySet())
      if (value.getKey().startsWith("orphan-"))
        entries.add(parseOrphan(value.getKey(), value.getValue()));
    return List.copyOf(entries);
  }

  private List<Entry> parsed(Map<String, byte[]> values) throws IOException {
    var entries = new ArrayList<Entry>();
    for (var value : values.entrySet())
      if (!value.getKey().startsWith("file-") && !value.getKey().startsWith("orphan-"))
        entries.add(parse(value.getKey(), value.getValue()));
    return List.copyOf(entries);
  }

  private List<FileEntry> parsedFiles(Map<String, byte[]> values) throws IOException {
    var entries = new ArrayList<FileEntry>();
    for (var value : values.entrySet())
      if (value.getKey().startsWith("file-"))
        entries.add(parseFile(value.getKey(), value.getValue()));
    return List.copyOf(entries);
  }

  private record Retirement(
      String name, Instant preparedAt, Instant suppressionUntil, Instant restoreFenceAt) {
    Retirement(String name, Instant preparedAt, Instant suppressionUntil) {
      this(name, preparedAt, suppressionUntil, preparedAt);
    }
  }

  public enum Verification {
    COMPLETE,
    INCOMPLETE,
    BUSY
  }

  public interface RetirementVerifier {
    Verification account(Entry entry) throws IOException;

    Verification file(FileEntry entry) throws IOException;

    Verification orphan(OrphanEntry entry) throws IOException;
  }

  /** File-only tooling cannot decide whether a database profile or metadata was suppressed. */
  public int pruneExpired(int batch) throws IOException {
    if (!catalog.enabled()) return 0;
    throw new IOException("Suppression retirement requires verified database coordination.");
  }

  public int pruneExpired(int batch, RetirementVerifier verifier) throws IOException {
    if (batch < 1 || batch > 10000 || verifier == null)
      throw new IllegalArgumentException("Invalid verified suppression retirement.");
    if (!catalog.enabled()) return 0;
    return catalog.locked(
        () -> {
          recover();
          var values = raw();
          assertIndex(values);
          var inventory = catalog.inventory();
          int removed = 0;
          PrivacyOperationFiles.pruneInterruptedTemporaries(root, clock.instant(), batch);
          var candidates = new ArrayList<Retirement>();
          var accounts = new HashMap<String, Entry>();
          for (var e : parsed(values))
            if (e.confirmedAt() != null) {
              String name = e.closure().toString();
              accounts.put(name, e);
              candidates.add(
                  new Retirement(name, e.preparedAt(), e.suppressionUntil(), e.confirmedAt()));
            }
          var files = new HashMap<String, FileEntry>();
          for (var e : parsedFiles(values))
            if (e.confirmedAt() != null) {
              String name = "file-" + e.file();
              files.put(name, e);
              candidates.add(
                  new Retirement(name, e.preparedAt(), e.suppressionUntil(), e.confirmedAt()));
            }
          var orphans = new HashMap<String, OrphanEntry>();
          for (var e : parsedOrphans(values))
            if (e.confirmedAbsentAt() != null) {
              String name = "orphan-" + e.file();
              orphans.put(name, e);
              candidates.add(
                  new Retirement(
                      name, e.preparedAt(), e.suppressionUntil(), e.confirmedAbsentAt()));
            }
          var order = Comparator.comparing(Retirement::preparedAt).thenComparing(Retirement::name);
          candidates.sort(order);
          int inspected = 0, start = 0;
          if (retirementAfter != null) {
            while (start < candidates.size()
                && order.compare(candidates.get(start), retirementAfter) <= 0) start++;
            if (start == candidates.size()) start = 0;
          }
          for (int n = 0; n < candidates.size(); n++) {
            var e = candidates.get((start + n) % candidates.size());
            if (removed >= batch) break;
            if (!e.suppressionUntil().isAfter(clock.instant())
                && !catalog.hasSurvivingCopyAtOrBefore(inventory, e.restoreFenceAt())) {
              if (inspected++ >= batch) break;
              retirementAfter = e;
              var account = accounts.get(e.name());
              var file = files.get(e.name());
              var orphan = orphans.get(e.name());
              Verification check =
                  account != null
                      ? verifier.account(account)
                      : file != null ? verifier.file(file) : verifier.orphan(orphan);
              if (check == Verification.BUSY) continue;
              if (check == Verification.INCOMPLETE) {
                if (account != null) reopenAccount(values, account, true);
                else if (file != null) reopenFile(values, file, true);
                else
                  persist(
                      values,
                      e.name(),
                      signedOrphan(
                          orphan.dataset(),
                          orphan.file(),
                          orphan.bytes(),
                          orphan.sha256(),
                          orphan.preparedAt(),
                          orphan.suppressionUntil(),
                          null),
                      true);
                values = raw();
                continue;
              }
              if (file != null && activeFiles.existsVerified(file.file().toString())) {
                reopenFile(values, file, true);
                values = raw();
                continue;
              }
              if (orphan != null && activeFiles.observe(orphan.file()).isPresent()) {
                reopenOrphan(values, orphan);
                values = raw();
                continue;
              }
              change(values, e.name(), null, e.restoreFenceAt());
              values = raw();
              removed++;
            }
          }
          return removed;
        });
  }

  public int rekey(int batch) throws IOException {
    if (catalog.enabled())
      return catalog.locked(
          () -> {
            recover();
            var values = raw();
            assertIndex(values);
            return rekey(values, batch, true);
          });
    return rekey(raw(), batch, false);
  }

  private int rekey(SortedMap<String, byte[]> values, int batch, boolean indexed)
      throws IOException {
    int changed = 0;
    for (var e : parsed(values)) {
      if (changed >= batch) break;
      if (e.version() == 3 && keys.current(JOURNAL).equals(e.keyId())) continue;
      var replacement =
          signed(e.owner(), e.closure(), e.preparedAt(), e.suppressionUntil(), e.confirmedAt());
      byte[] bytes = json.writeValueAsBytes(replacement);
      if (indexed) {
        change(values, e.closure().toString(), bytes, Instant.EPOCH);
        values = raw();
      } else
        PrivacyOperationFiles.atomicWrite(
            PrivacyOperationFiles.child(root, e.closure().toString()), bytes);
      changed++;
    }
    for (var e : parsedFiles(values)) {
      if (changed >= batch) break;
      if (e.version() == 2 && keys.current(JOURNAL).equals(e.keyId())) continue;
      var replacement =
          signedFile(e.owner(), e.file(), e.preparedAt(), e.suppressionUntil(), e.confirmedAt());
      byte[] bytes = json.writeValueAsBytes(replacement);
      String name = "file-" + e.file();
      if (indexed) {
        change(values, name, bytes, Instant.EPOCH);
        values = raw();
      } else PrivacyOperationFiles.atomicWrite(PrivacyOperationFiles.child(root, name), bytes);
      changed++;
    }
    for (var e : parsedOrphans(values)) {
      if (changed >= batch) break;
      if (keys.current(JOURNAL).equals(e.keyId())) continue;
      var replacement =
          signedOrphan(
              e.dataset(),
              e.file(),
              e.bytes(),
              e.sha256(),
              e.preparedAt(),
              e.suppressionUntil(),
              e.confirmedAbsentAt());
      byte[] bytes = json.writeValueAsBytes(replacement);
      String name = "orphan-" + e.file();
      if (indexed) {
        change(values, name, bytes, Instant.EPOCH);
        values = raw();
      } else throw new IOException("Orphan suppression cannot lose its dataset index.");
      changed++;
    }
    return changed;
  }
}
