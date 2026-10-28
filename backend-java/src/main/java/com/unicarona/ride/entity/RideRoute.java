package com.unicarona.ride.entity;

import com.unicarona.ride.dto.RideDto;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity @Table(name="ride_routes") @Immutable
public class RideRoute {
    @Id private UUID id;
    @Column(name="university_id") private UUID universityId;
    @Column(name="driver_id") private UUID driverId;
    private String region,meeting;
    @Column(name="distance_m") private int distanceM;
    @Column(name="created_at",insertable=false,updatable=false) private Instant createdAt;
    protected RideRoute() {}
    public RideRoute(UUID universityId,UUID driverId,String region,String meeting,int distanceM) {
        id=UUID.randomUUID();this.universityId=universityId;this.driverId=driverId;this.region=region;this.meeting=meeting;this.distanceM=distanceM;
    }
    public RideRoute(UUID universityId,UUID driverId,int distanceM,RideDto.Create input) {
        id=UUID.randomUUID();this.universityId=universityId;this.driverId=driverId;this.distanceM=distanceM;
        region=input.region();meeting=input.meeting();
    }
    public RideRoute copyForDistance(int distance) {
        var result=new RideRoute();result.id=UUID.randomUUID();result.universityId=universityId;result.driverId=driverId;
        result.region=region;result.meeting=meeting;result.distanceM=distance;return result;
    }
    public UUID getId() { return id; }
}
