package com.unicarona.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.notification.provider.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class EmailProviderConfiguration {
    @Bean @ConditionalOnMissingBean(EmailProvider.class) EmailProvider emailProvider(EmailSettings settings,ObjectMapper json) {
        return switch(settings.provider()) {
            case "development" -> new DevelopmentEmailProvider(settings.developmentPath(),settings.publicRoot(),json);
            case "resend" -> new ResendEmailProvider(settings,json);
            default -> new SmtpEmailProvider(settings);
        };
    }
}
