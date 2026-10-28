package com.unicarona.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.DynamicUpdate;

@Entity @Table(name="transfers") @DynamicUpdate
public class Transfer {
    @Id private UUID id;
    @Column(name="user_id") private UUID userId;
    private String type;
    private int cents;
    private String status,cadence;
    @Column(name="provider_reference") private String providerReference;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at") private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected Transfer() {}
    public Transfer(UUID user,String type,int cents,String cadence) { id=UUID.randomUUID();userId=user;this.type=type;this.cents=cents;this.cadence=cadence;status="pending"; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getType() { return type; }
    public int getCents() { return cents; }
    public String getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public void complete(String status) { this.status=status; }
}
