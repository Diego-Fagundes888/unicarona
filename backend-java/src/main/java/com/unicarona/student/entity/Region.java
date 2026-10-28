package com.unicarona.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "regions")
public class Region {
    @Id private UUID id;
    private String slug;
    private String name;
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "state_code", length = 2, columnDefinition = "char(2)") private String stateCode;
    protected Region() {}
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getStateCode() { return stateCode; }
}
