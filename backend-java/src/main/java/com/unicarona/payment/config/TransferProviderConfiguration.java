package com.unicarona.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.payment.provider.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.*;

@AutoConfiguration
public class TransferProviderConfiguration {
    @Bean @ConditionalOnMissingBean WebhookAuthentication webhookAuthentication(BillingSettings settings,ObjectMapper json) { return new WebhookAuthentication(settings.secret(),json,Clock.systemUTC()); }
    @Bean @ConditionalOnMissingBean TransferProvider transferProvider(BillingSettings settings,WebhookAuthentication authentication) {
        if(!"development".equals(settings.provider())) throw new IllegalStateException("Transfer provider adapter has not been configured and homologated.");
        return new DevelopmentTransferProvider(settings,authentication);
    }
}
