package com.unicarona.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "courses")
public class Course {
    @Id private UUID id;
    @Column(name = "university_id") private UUID universityId;
    private String slug;
    private String name;
    private boolean active;
    protected Course() {}
    public UUID getId() { return id; }
    public UUID getUniversityId() { return universityId; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
