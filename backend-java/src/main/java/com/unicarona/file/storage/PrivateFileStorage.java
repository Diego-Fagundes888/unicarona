package com.unicarona.file.storage;

import com.unicarona.file.config.StorageSettings;
import com.unicarona.shared.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PrivateFileStorage {
  private static final Logger log = LoggerFactory.getLogger(PrivateFileStorage.class);
  private final Path root, publicRoot;

  @Autowired
  public PrivateFileStorage(StorageSettings settings) {
    this(settings.root(), settings.publicRoot());
  }

  public PrivateFileStorage(Path root, Path publicRoot) {
    this.root = root.toAbsolutePath().normalize();
    this.publicRoot = publicRoot.toAbsolutePath().normalize();
    assertPrivate(this.root, this.publicRoot);
  }

  private static void assertPrivate(Path actual, Path publicPath) {
    ApiException.require(
        !actual.startsWith(publicPath),
        "INVALID_STORAGE",
        "Arquivos privados não podem ficar na pasta pública.",
        500);
  }

  private Path verifiedRoot() throws IOException {
    Path actual = root.toRealPath();
    assertPrivate(actual, Files.exists(publicRoot) ? publicRoot.toRealPath() : publicRoot);
    if (Files.isSymbolicLink(root) || !actual.equals(root))
      throw new IOException("Redirected private storage root.");
    return actual;
  }

  private Path file(Path actual, String key) {
    ApiException.require(
        key != null && key.matches("[a-f0-9-]{36}"), "INVALID_FILE", "Arquivo inválido.", 404);
    return actual.resolve(key);
  }

  public void put(String key, byte[] data) throws IOException {
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    if (posix)
      Files.createDirectories(
          root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    else Files.createDirectories(root);
    Path target = file(verifiedRoot(), key);
    boolean created = false;
    try (var channel =
        posix
            ? Files.newByteChannel(
                target,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            : Files.newByteChannel(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      created = true;
      ByteBuffer bytes = ByteBuffer.wrap(data);
      while (bytes.hasRemaining()) channel.write(bytes);
    } catch (IOException error) {
      if (created)
        try {
          Files.deleteIfExists(target);
        } catch (IOException cleanup) {
          error.addSuppressed(cleanup);
        }
      throw error;
    }
  }

  public byte[] get(String key) throws IOException {
    Path target = file(verifiedRoot(), key);
    ApiException.require(!Files.isSymbolicLink(target), "INVALID_FILE", "Arquivo inválido.", 404);
    try (var stream =
        Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes(5 * 1024 * 1024 + 1);
      if (bytes.length > 5 * 1024 * 1024)
        throw new IOException("Stored file exceeds the supported size.");
      return bytes;
    }
  }

  public void remove(String key) {
    try {
      Files.deleteIfExists(file(verifiedRoot(), key));
    } catch (NoSuchFileException ignored) {
    } catch (IOException error) {
      log.warn("Private file cleanup failed: fileId={}, code=STORAGE_ERROR", key);
    } catch (ApiException error) {
      log.warn("Private file cleanup failed: fileId={}, code={}", key, error.code());
    }
  }

  /** Erasure processing needs an observable failure, not a best-effort cleanup. */
  public void eraseVerified(String key) throws IOException {
    try {
      Files.deleteIfExists(file(verifiedRoot(), key));
    } catch (NoSuchFileException missing) {
      // The root or file is absent; a denied/unreadable path is not treated as erased.
      if (!key.matches("[a-f0-9-]{36}")) throw new IOException("Invalid erasure key.");
    }
  }

  public boolean existsVerified(String key) throws IOException {
    try {
      Files.readAttributes(
          file(verifiedRoot(), key),
          java.nio.file.attribute.BasicFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS);
      return true;
    } catch (NoSuchFileException missing) {
      return false;
    }
  }

  public record Observation(UUID file, int bytes, String sha256, String fingerprint) {
    @Override
    public String toString() {
      return "PrivateFileObservation[redacted]";
    }
  }

  private static String sha(byte[] value) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA256 unavailable");
    }
  }

  /**
   * Only this application's flat UUID namespace is eligible. No recursive traversal, arbitrary
   * path, redirected file or operator-created note is silently treated as disposable data.
   */
  public List<UUID> inventory(UUID after, int limit) throws IOException {
    if (limit < 1 || limit > 10000)
      throw new IllegalArgumentException("Invalid private file inventory limit.");
    Path actual;
    try {
      actual = verifiedRoot();
    } catch (NoSuchFileException absent) {
      return List.of();
    }
    var names = new ArrayList<String>();
    try (var entries = Files.newDirectoryStream(actual)) {
      for (Path path : entries) {
        if (names.size() >= 100000)
          throw new IOException("Private file inventory exceeds its supported size.");
        String name = path.getFileName().toString();
        UUID id;
        try {
          id = UUID.fromString(name);
        } catch (IllegalArgumentException invalid) {
          throw new IOException("Unexpected private storage entry.");
        }
        if (!id.toString().equals(name)
            || Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !path.equals(path.toRealPath()))
          throw new IOException("Unexpected or redirected private storage entry.");
        names.add(name);
      }
    }
    return names.stream()
        .sorted()
        .filter(name -> after == null || name.compareTo(after.toString()) > 0)
        .limit(limit)
        .map(UUID::fromString)
        .toList();
  }

  /**
   * Fingerprint includes content and filesystem identity. It is an internal observation, not an
   * assertion of ownership and not anonymization. Call under per-file coordination.
   */
  public Optional<Observation> observe(UUID id) throws IOException {
    try {
      Path path = file(verifiedRoot(), id.toString());
      if (Files.isSymbolicLink(path) || !path.equals(path.toRealPath()))
        throw new IOException("Redirected private file.");
      var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile() || before.size() > 5 * 1024 * 1024)
        throw new IOException("Unsupported private file observation.");
      byte[] bytes = get(id.toString());
      var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (before.size() != after.size()
          || bytes.length != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !before.creationTime().equals(after.creationTime())
          || !Objects.equals(before.fileKey(), after.fileKey())
          || !path.equals(path.toRealPath()))
        throw new IOException("Private file changed during observation.");
      String hash = sha(bytes),
          identity =
              hash
                  + "|"
                  + after.size()
                  + "|"
                  + after.lastModifiedTime()
                  + "|"
                  + after.creationTime()
                  + "|"
                  + after.fileKey();
      return Optional.of(
          new Observation(
              id,
              bytes.length,
              hash,
              sha(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    } catch (NoSuchFileException missing) {
      return Optional.empty();
    }
  }

  public void eraseExact(UUID id, int expectedBytes, String expectedSha256) throws IOException {
    var actual = observe(id);
    if (actual.isEmpty()) return;
    var value = actual.get();
    if (value.bytes() != expectedBytes || !value.sha256().equals(expectedSha256))
      throw new IOException("Private file conflicts with the signed suppression content.");
    eraseVerified(id.toString());
  }
}
