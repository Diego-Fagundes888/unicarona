package com.unicarona.payment.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="webhook_events")
public class WebhookReceipt {
    @Id private UUID id;
    private String status;
    @Column(name="payload_hash") private String payloadHash;
    protected WebhookReceipt() {}
    public UUID getId() { return id; }
    public String getStatus() { return status; }
    public String getPayloadHash() { return payloadHash; }
}
