package com.unicarona.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "universities")
public class University {
    @Id private UUID id;
    private String slug;
    private String name;
    private String city;
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "state_code", length = 2, columnDefinition = "char(2)") private String stateCode;
    private boolean active;
    @Column(name = "time_zone") private String timeZone;
    protected University() {}
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getStateCode() { return stateCode; }
    public boolean isActive() { return active; }
    public String getTimeZone() { return timeZone; }
}
