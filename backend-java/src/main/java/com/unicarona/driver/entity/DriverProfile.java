package com.unicarona.driver.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="driver_profiles")
public class DriverProfile {
    @Id @Column(name="user_id") private UUID userId;
    private String status;
    @Column(name="approved_at") private Instant approvedAt;
    protected DriverProfile() {}
    public String getStatus() { return status; }
}
