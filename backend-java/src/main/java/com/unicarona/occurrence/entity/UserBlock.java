package com.unicarona.occurrence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="user_blocks")
public class UserBlock {
    @Id private UUID id;
    @Column(name="by_id") private UUID byId;
    @Column(name="other_id") private UUID otherId;
    private boolean active;
    @Column(name="created_at") private Instant createdAt;
    @Column(name="updated_at") private Instant updatedAt;
    protected UserBlock() {}
}
