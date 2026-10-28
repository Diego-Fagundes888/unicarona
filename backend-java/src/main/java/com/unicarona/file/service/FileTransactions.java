package com.unicarona.file.service;

import com.unicarona.file.config.StorageSettings;
import com.unicarona.file.dto.FileDto;
import com.unicarona.file.entity.StoredFile;
import com.unicarona.file.repository.FileAccessRepository;
import com.unicarona.file.repository.StoredFileRepository;
import com.unicarona.file.storage.*;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.repository.ActorsRepository;
import java.io.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class FileTransactions {
  private final ActorsRepository actors;
  private final StoredFileRepository files;
  private final FileAccessRepository access;
  private final StorageSettings settings;
  private final PrivateFileStorage storage;
  private final JdbcTemplate sql;

  public FileTransactions(
      ActorsRepository actors,
      StoredFileRepository files,
      FileAccessRepository access,
      StorageSettings settings,
      PrivateFileStorage storage,
      JdbcTemplate sql) {
    this.actors = actors;
    this.files = files;
    this.access = access;
    this.settings = settings;
    this.storage = storage;
    this.sql = sql;
  }

  /**
   * The lock covers physical creation through database completion. An ambiguous commit must leave
   * bytes intact: the orphan collector can classify them only after checking metadata.
   */
  @Transactional
  public FileDto upload(
      Actor actor, UUID id, String purpose, ImageProcessor.Prepared image, String digest) {
    sql.execute("SET LOCAL lock_timeout='3s'");
    ActorsRepository.active(actors.lockUser(actor.id()));
    PrivateFileLock.acquire(sql, id);
    boolean[] created = {false}, duplicate = {false};
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int outcome) {
            if (created[0]
                && (outcome == STATUS_ROLLED_BACK || (outcome == STATUS_COMMITTED && duplicate[0])))
              try {
                storage.eraseVerified(id.toString());
              } catch (IOException unavailable) {
                org.slf4j.LoggerFactory.getLogger(FileTransactions.class)
                    .warn("event=upload_cleanup_pending code=PRIVATE_UPLOAD_CLEANUP_RETRY");
              }
          }
        });
    try {
      storage.put(id.toString(), image.data());
      created[0] = true;
    } catch (IOException unavailable) {
      throw new UncheckedIOException("Private upload storage is unavailable.", unavailable);
    }
    var result = store(actor, id, purpose, image, digest);
    duplicate[0] = !result.id().equals(id);
    return result;
  }

  @Transactional
  public FileDto store(
      Actor actor, UUID id, String purpose, ImageProcessor.Prepared image, String digest) {
    ActorsRepository.active(actors.lockUser(actor.id()));
    var existing =
        files.findFirstByOwnerIdAndPurposeAndSha256AndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
            actor.id(), purpose, digest);
    if (existing.isPresent()) {
      var entry = existing.get();
      access.uploaded(entry.getId());
      if ("photo".equals(purpose)) access.photo(actor.id(), entry.getId());
      return new FileDto(entry.getId(), purpose, entry.getContentType(), entry.getByteSize());
    }
    var usage = access.usage(actor.id());
    ApiException.require(
        usage.count() < settings.maximumFiles()
            && usage.bytes() + image.data().length <= settings.quota(),
        "FILE_QUOTA",
        "Sua conta atingiu o limite de arquivos. Procure o suporte para revisar os documentos armazenados.",
        409);
    files.saveAndFlush(
        new StoredFile(id, actor.id(), purpose, image.contentType(), image.data().length, digest));
    if ("photo".equals(purpose)) access.photo(actor.id(), id);
    return new FileDto(id, purpose, image.contentType(), image.data().length);
  }
}
