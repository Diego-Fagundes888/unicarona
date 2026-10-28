package com.unicarona.notification.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity @Table(name="notifications")
public class Notification {
    @Id private UUID id;
    @Column(name="user_id") private UUID userId;
    private String title;
    private String body;
    private String route;
    @Column(name="ref_id") private UUID reference;
    @Column(name="read_at") private Instant readAt;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at") private Instant createdAt;
    protected Notification() {}
    public Notification(UUID user,String title,String body,String route,UUID reference) {
        this.id=UUID.randomUUID();this.userId=user;this.title=title;this.body=body;this.route=route;this.reference=reference;
    }
    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getRoute() { return route; }
    public UUID getReference() { return reference; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
