package com.unicarona.ride.entity;

import com.unicarona.ride.dto.RideDto;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="rides") @DynamicUpdate
public class Ride {
    @Id private UUID id;
    @Column(name="university_id") private UUID universityId;
    @Column(name="route_id") private UUID routeId;
    @Column(name="routine_id") private UUID routineId;
    @Column(name="driver_id") private UUID driverId;
    @Column(name="vehicle_id") private UUID vehicleId;
    private String direction;
    @Column(name="ride_date") @JdbcTypeCode(SqlTypes.LOCAL_DATE) private LocalDate rideDate;
    @Column(name="departure_time") @JdbcTypeCode(SqlTypes.LOCAL_TIME) private LocalTime departureTime;
    private short capacity;
    @Column(name="price_cents") private int priceCents;
    @Column(name="commission_bps") private int commissionBps;
    @Column(name="pricing_version_id") private UUID pricingVersionId;
    @Column(name="toll_cents") private int tollCents;
    private boolean automatic;
    private String notes,status;
    @Column(name="proposed_time") @JdbcTypeCode(SqlTypes.LOCAL_TIME) private LocalTime proposedTime;
    private int version;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected Ride() {}
    public Ride(UUID university,UUID route,UUID driver,UUID vehicle,RideDto.Create input,UUID pricing,int price,int bps,int toll) {
        id=UUID.randomUUID();universityId=university;routeId=route;driverId=driver;vehicleId=vehicle;direction=input.direction();
        rideDate=LocalDate.parse(input.date());departureTime=LocalTime.parse(input.time());capacity=input.capacity().shortValue();priceCents=price;
        commissionBps=bps;pricingVersionId=pricing;tollCents=toll;automatic=input.automatic();notes=input.notes()==null?"":input.notes();status="planned";version=1;
    }
    public UUID getId() { return id; }
    public UUID getUniversityId() { return universityId; }
    public UUID getRouteId() { return routeId; }
    public UUID getVehicleId() { return vehicleId; }
    public int getCapacity() { return capacity; }
    public String getStatus() { return status; }
    public int getVersion() { return version; }
    public void edit(UUID route,RideDto.Edit input,UUID pricing,int price,int bps,int toll) {
        routeId=route;capacity=input.capacity().shortValue();priceCents=price;commissionBps=bps;pricingVersionId=pricing;tollCents=toll;
        automatic=input.automatic();notes=input.notes()==null?"":input.notes();version++;
    }
}
