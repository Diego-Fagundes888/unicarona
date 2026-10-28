package com.unicarona.file.repository;

import com.unicarona.file.entity.StoredFile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile,UUID> {
    Optional<StoredFile> findFirstByOwnerIdAndPurposeAndSha256AndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID owner,String purpose,String hash);
    Optional<StoredFile> findByIdAndDeletedAtIsNull(UUID id);
}
