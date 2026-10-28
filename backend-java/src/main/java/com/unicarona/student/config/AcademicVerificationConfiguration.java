package com.unicarona.student.config;

import com.unicarona.student.dto.IdentityDto;
import com.unicarona.student.service.AcademicVerificationProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AcademicVerificationConfiguration {
    @Bean @ConditionalOnMissingBean(AcademicVerificationProvider.class)
    AcademicVerificationProvider administrativeAcademicVerification() {
        return (user,university) -> new IdentityDto.Verification("pending","administrative");
    }
}
