package com.unicarona.subscription.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="subscriptions") @DynamicUpdate
public class Subscription {
    @Id private UUID id;
    @Column(name="routine_id") private UUID routineId;
    @Column(name="driver_id") private UUID driverId;
    @Column(name="passenger_id") private UUID passengerId;
    @Column(name="class_id") private UUID classId;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE) @Column(name="start_date") private LocalDate startDate;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE) @Column(name="end_date") private LocalDate endDate;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition="smallint[]") private Short[] weekdays;
    private String directions,status;
    @Column(name="total_cents") private int totalCents;
    @Column(name="discount_bps") private int discountBps;
    private boolean renew;
    @Column(name="grace_until") private Instant graceUntil;
    @Column(name="previous_subscription_id") private UUID previousSubscriptionId;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected Subscription() {}
    public Subscription(UUID routine,UUID driver,UUID passenger,UUID classId,String start,String end,List<Integer> days,String directions,int total,int discount,UUID previous) {
        id=UUID.randomUUID();routineId=routine;driverId=driver;passengerId=passenger;this.classId=classId;startDate=LocalDate.parse(start);endDate=LocalDate.parse(end);weekdays=days.stream().map(Integer::shortValue).toArray(Short[]::new);this.directions=directions;totalCents=total;discountBps=discount;previousSubscriptionId=previous;status="active";renew=true;
    }
    public UUID getId() { return id; }
    public UUID getRoutineId() { return routineId; }
    public UUID getDriverId() { return driverId; }
    public UUID getPassengerId() { return passengerId; }
    public String getStatus() { return status; }
    public String getDirections() { return directions; }
    public List<Integer> getWeekdays() { return Arrays.stream(weekdays).map(Short::intValue).toList(); }
}
