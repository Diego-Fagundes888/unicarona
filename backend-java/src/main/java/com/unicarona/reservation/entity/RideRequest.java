package com.unicarona.reservation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="ride_requests") @DynamicUpdate
public class RideRequest {
    @Id private UUID id;
    @Column(name="passenger_id") private UUID passengerId;
    @Column(name="driver_id") private UUID driverId;
    @Column(name="ride_id") private UUID rideId;
    @Column(name="routine_id") private UUID routineId;
    @Column(name="previous_subscription_id") private UUID previousSubscriptionId;
    private String type,status,reason;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private JsonNode preview;
    @Column(name="expires_at") private Instant expiresAt;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected RideRequest() {}
    public RideRequest(UUID passenger,UUID driver,UUID ride,UUID routine,UUID previous,String type,boolean automatic,JsonNode preview,Instant expires) {
        id=UUID.randomUUID();passengerId=passenger;driverId=driver;rideId=ride;routineId=routine;previousSubscriptionId=previous;this.type=type;status=automatic?"accepted":"pending";this.preview=preview;expiresAt=expires;
    }
    public UUID getId() { return id; }
    public UUID getPassengerId() { return passengerId; }
    public UUID getDriverId() { return driverId; }
    public UUID getRideId() { return rideId; }
    public UUID getRoutineId() { return routineId; }
    public UUID getPreviousSubscriptionId() { return previousSubscriptionId; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public JsonNode getPreview() { return preview; }
    public Instant getExpiresAt() { return expiresAt; }
    public void respond(boolean accept) { status=accept?"accepted":"rejected"; }
    public void cancel() { status="cancelled"; }
    public void cancel(String reason) { status="cancelled";this.reason=reason; }
    public void confirm() { status="confirmed"; }
}
