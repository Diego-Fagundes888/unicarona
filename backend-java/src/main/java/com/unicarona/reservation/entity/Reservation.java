package com.unicarona.reservation.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="ride_reservations") @DynamicUpdate
public class Reservation {
    @Id private UUID id;
    @Column(name="request_id") private UUID requestId;
    @Column(name="ride_id") private UUID rideId;
    @Column(name="passenger_id") private UUID passengerId;
    @Column(name="driver_id") private UUID driverId;
    @Column(name="subscription_id") private UUID subscriptionId;
    @Column(name="payment_id") private UUID paymentId;
    private String status;
    @Column(name="pin_ciphertext") private String pinCiphertext;
    @Column(name="pin_hash") private String pinHash;
    @Column(name="price_cents") private int priceCents;
    @Column(name="boarded_at") private Instant boardedAt;
    @Column(name="final_response") private String finalResponse;
    private String availability,absence;
    @Column(name="absence_reason") private String absenceReason;
    @Column(name="confirmation_deadline") private Instant confirmationDeadline;
    @JdbcTypeCode(SqlTypes.LOCAL_TIME) @Column(name="proposed_time") private LocalTime proposedTime;
    @Column(name="time_response") private String timeResponse;
    @Column(name="cancel_reason") private String cancelReason;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected Reservation() {}
    public Reservation(UUID request,UUID ride,UUID passenger,UUID driver,UUID subscription,UUID payment,String cipher,String hash,int cents) {
        id=UUID.randomUUID();requestId=request;rideId=ride;passengerId=passenger;driverId=driver;subscriptionId=subscription;paymentId=payment;pinCiphertext=cipher;pinHash=hash;priceCents=cents;status="confirmed";finalResponse="pending";
    }
    public UUID getId() { return id; }
}
