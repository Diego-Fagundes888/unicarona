package com.unicarona.subscription.entity;

import com.unicarona.subscription.dto.SubscriptionDto;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="routines") @DynamicUpdate
public class Routine {
    @Id private UUID id;
    @Column(name="route_id") private UUID routeId;
    @Column(name="driver_id") private UUID driverId;
    @Column(name="university_id") private UUID universityId;
    @Column(name="vehicle_id") private UUID vehicleId;
    @Column(name="class_id") private UUID classId;
    @JdbcTypeCode(SqlTypes.ARRAY) private short[] weekdays;
    @Column(name="ida_time") @JdbcTypeCode(SqlTypes.LOCAL_TIME) private LocalTime idaTime;
    @Column(name="volta_time") @JdbcTypeCode(SqlTypes.LOCAL_TIME) private LocalTime voltaTime;
    @Column(name="ida_cents") private Integer idaCents;
    @Column(name="volta_cents") private Integer voltaCents;
    private short capacity;
    private boolean automatic;
    private String status,notes;
    @Column(name="pricing_version_id") private UUID pricingVersionId;
    @CreationTimestamp(source=SourceType.DB) @Column(name="created_at",updatable=false) private Instant createdAt;
    @UpdateTimestamp(source=SourceType.DB) @Column(name="updated_at") private Instant updatedAt;
    protected Routine() {}
    public Routine(UUID route,UUID driver,UUID university,UUID vehicle,UUID classId,UUID pricing,int price,SubscriptionDto.RoutineInput input) {
        id=UUID.randomUUID();routeId=route;driverId=driver;universityId=university;vehicleId=vehicle;this.classId=classId;pricingVersionId=pricing;
        weekdays=new short[input.weekdays().size()];for(int i=0;i<weekdays.length;i++) weekdays[i]=input.weekdays().get(i).shortValue();
        boolean ida=!"volta".equals(input.direction()),volta=!"ida".equals(input.direction());
        idaTime=ida?LocalTime.parse(input.time()):null;voltaTime=volta?LocalTime.parse(input.returnTime()==null?input.time():input.returnTime()):null;
        idaCents=ida?price:null;voltaCents=volta?price:null;capacity=input.capacity().shortValue();automatic=input.automatic();notes=input.notes()==null?"":input.notes();status="active";
    }
    public UUID getId() { return id; }
    public UUID getClassId() { return classId; }
    public String getStatus() { return status; }
    public void availability(boolean enabled) { status=enabled?"active":"paused"; }
}
