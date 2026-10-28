package com.unicarona.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity @Table(name="parcels") @DynamicUpdate
public class Parcel {
    @Id private UUID id;
    @Column(name="reservation_id") private UUID reservationId;
    @Column(name="payment_id") private UUID paymentId;
    @Column(name="passenger_id") private UUID passengerId;
    @Column(name="driver_id") private UUID driverId;
    private int cents;
    @Column(name="commission_bps") private int commissionBps;
    private String status,reason;
    @Column(name="commission_cents") private int commissionCents;
    @Column(name="driver_cents") private int driverCents;
    @Column(name="settled_at") private Instant settledAt;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    protected Parcel() {}
    public Parcel(UUID reservation,UUID payment,UUID passenger,UUID driver,int cents,int bps) { id=UUID.randomUUID();reservationId=reservation;paymentId=payment;passengerId=passenger;driverId=driver;this.cents=cents;commissionBps=bps;status="future"; }
    public UUID getId() { return id; }
    public UUID getReservationId() { return reservationId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getPassengerId() { return passengerId; }
    public UUID getDriverId() { return driverId; }
    public int getCents() { return cents; }
    public int getCommissionBps() { return commissionBps; }
    public String getStatus() { return status; }
    public void block(String reason) { status="blocked";this.reason=reason; }
    public void settle(String status,String reason,int fee,int net,Instant time) { this.status=status;this.reason=reason;commissionCents=fee;driverCents=net;settledAt=time; }
}
