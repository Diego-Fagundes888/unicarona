package com.unicarona.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity @Table(name="ledger_transactions")
public class LedgerHeader {
    @Id private UUID id;
    @Column(name="event_key") private String eventKey;
    private String kind,reason;
    @Column(name="payment_id") private UUID paymentId;
    @Column(name="parcel_id") private UUID parcelId;
    @Column(name="body_hash") private String bodyHash;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at") private Instant createdAt;
    protected LedgerHeader() {}
    public LedgerHeader(String key,String kind,String reason,UUID payment,UUID parcel,String hash) {
        id=UUID.randomUUID();eventKey=key;this.kind=kind;this.reason=reason;paymentId=payment;parcelId=parcel;bodyHash=hash;
    }
    public UUID getId() { return id; }
    public String getBodyHash() { return bodyHash; }
}
