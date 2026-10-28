package com.unicarona.file.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity @Table(name="stored_files")
public class StoredFile {
    @Id private UUID id;
    @Column(name="owner_id") private UUID ownerId;
    private String purpose;
    @Column(name="storage_key") private String storageKey;
    @Column(name="content_type") private String contentType;
    @Column(name="byte_size") private int byteSize;
    private String sha256;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at") private Instant createdAt;
    @Column(name="deleted_at") private Instant deletedAt;
    protected StoredFile() {}
    public StoredFile(UUID id,UUID owner,String purpose,String type,int bytes,String hash) {
        this.id=id;this.ownerId=owner;this.purpose=purpose;this.storageKey=id.toString();this.contentType=type;this.byteSize=bytes;this.sha256=hash;
    }
    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getPurpose() { return purpose; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public int getByteSize() { return byteSize; }
}
