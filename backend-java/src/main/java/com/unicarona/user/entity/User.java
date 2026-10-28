package com.unicarona.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "users")
public class User {
    @Id private UUID id;
    private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "email_verified_at") private Instant emailVerifiedAt;
    @Column(name = "deleted_at") private Instant deletedAt;
    protected User() {}
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
}
