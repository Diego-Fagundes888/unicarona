package com.unicarona.user.dto;

import java.util.UUID;

public record ProfileDto(UUID id, String name, String email, String birth, String phone, String role,
    String accountStatus, boolean emailVerified, UUID universityId, String university, UUID courseId,
    String course, String periodStatus, Integer periodNumber, String classLabel, UUID classId, String shift,
    UUID regionId, String region, String verificationStatus, String driverStatus, String photo, Settings settings) {
    public record Settings(Boolean notices, String withdrawal) {}
}
