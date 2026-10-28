package com.unicarona.student.service;

import com.unicarona.student.dto.IdentityDto;
import java.util.UUID;

public interface AcademicVerificationProvider {
    IdentityDto.Verification verify(UUID user,UUID university);
}
