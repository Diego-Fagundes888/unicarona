package com.unicarona.payment.config;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.provider.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after=TransferProviderConfiguration.class)
public class PaymentProviderConfiguration {
    @Bean @ConditionalOnMissingBean PaymentProvider paymentProvider(BillingSettings settings,AuthSettings auth,WebhookAuthentication authentication) {
        if(!"development".equals(settings.provider())) throw new IllegalStateException("Payment provider adapter has not been configured and homologated.");
        return new DevelopmentPaymentProvider(settings,auth,authentication);
    }
}
