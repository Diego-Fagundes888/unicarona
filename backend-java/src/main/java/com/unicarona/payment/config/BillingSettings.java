package com.unicarona.payment.config;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class BillingSettings {
    private final String provider,secret;
    private final int timeoutMs;
    private final boolean production;
    public BillingSettings(Environment env) {
        provider=env.getProperty("PAYMENT_PROVIDER","development");secret=env.getProperty("PAYMENT_WEBHOOK_SECRET","");
        timeoutMs=env.getProperty("PAYMENT_PROVIDER_TIMEOUT_MS",Integer.class,10000);
        String mode=env.getProperty("NODE_ENV","development");production="production".equals(mode);
        if(!Set.of("development","test","production").contains(mode) || !Set.of("development","mercadopago").contains(provider)
            || secret.length()<32 || timeoutMs<100 || timeoutMs>20000) throw new IllegalStateException("Invalid billing configuration; check provider, webhook secret and timeout.");
        if(production && ("development".equals(provider) || "development".equals(env.getProperty("EMAIL_PROVIDER","development"))))
            throw new IllegalStateException("Development providers are forbidden in production.");
        if("mercadopago".equals(provider) && env.getProperty("MERCADO_PAGO_ACCESS_TOKEN",env.getProperty("PAYMENT_API_TOKEN","")).isEmpty()) throw new IllegalStateException("MERCADO_PAGO_ACCESS_TOKEN is required for this provider.");
    }
    public String provider() { return provider; }
    public String secret() { return secret; }
    public int timeoutMs() { return timeoutMs; }
    public boolean production() { return production; }
}
