package com.unicarona.occurrence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SourceType;

@Entity @Table(name="occurrences") @DynamicUpdate
public class Occurrence {
    @Id private UUID id;
    @Column(name="reporter_id") private UUID reporterId;
    @Column(name="other_id") private UUID otherId;
    @Column(name="reservation_id") private UUID reservationId;
    private String kind,reason,status,outcome,decision;
    private boolean severe;
    @Column(name="reviewed_by") private UUID reviewedBy;
    @Column(name="reviewed_at") private Instant reviewedAt;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    protected Occurrence() {}
    public Occurrence(UUID reporter,UUID other,UUID reservation,String reason,String kind) { id=UUID.randomUUID();reporterId=reporter;otherId=other;reservationId=reservation;this.reason=reason;this.kind=kind;status="pending"; }
    public Occurrence(UUID reporter,UUID other,UUID reservation,String reason,boolean severe) { this(reporter,other,reservation,reason,"safety");this.severe=severe; }
    public UUID getId() { return id; }
    public static Occurrence financialReview(UUID reporter,UUID other,String reason) { var report=new Occurrence(reporter,other,null,reason,"safety");report.status="financial_pending";return report; }
}
