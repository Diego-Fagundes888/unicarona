package com.unicarona.student.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="student_profiles")
public class StudentProfile {
    @Id @Column(name="user_id") private UUID userId;
    @Column(name="university_id") private UUID universityId;
    @Column(name="course_id") private UUID courseId;
    @Column(name="class_id") private UUID classId;
    @Column(name="verification_status") private String verificationStatus;
    @Column(name="verification_source") private String verificationSource;
    @Column(name="verified_at") private Instant verifiedAt;
    private boolean notices;
    @Column(name="withdrawal_cadence") private String withdrawalCadence;
    protected StudentProfile() {}
    public String getVerificationStatus() { return verificationStatus; }
}
