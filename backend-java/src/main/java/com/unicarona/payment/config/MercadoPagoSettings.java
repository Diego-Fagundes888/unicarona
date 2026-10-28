package com.unicarona.payment.config;

import java.net.URI;
import org.springframework.core.env.Environment;

/** Private server configuration. Do not infer test mode from the APP_USR prefix. */
public record MercadoPagoSettings(String accessToken, String publicKey, String webhookSecret,
                                  String environment, String origin, int timeoutMs) {
    public static MercadoPagoSettings from(Environment env) {
        String token=env.getProperty("MERCADO_PAGO_ACCESS_TOKEN",env.getProperty("PAYMENT_API_TOKEN",""));
        String secret=env.getProperty("MERCADO_PAGO_WEBHOOK_SECRET","");
        String mode=env.getProperty("MERCADO_PAGO_ENVIRONMENT","test");
        String origin=env.getProperty("APP_ORIGIN","");
        if(token.isBlank() || secret.length()<32 || !java.util.Set.of("test","production").contains(mode))
            throw new IllegalStateException("Configure Mercado Pago access token, webhook secret and explicit environment.");
        URI uri=URI.create(origin);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || (uri.getPath()!=null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
            || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && java.util.Set.of("localhost","127.0.0.1").contains(uri.getHost()))))
            throw new IllegalStateException("Mercado Pago requires a valid APP_ORIGIN; production must use HTTPS.");
        if("production".equals(mode) && (!"https".equals(uri.getScheme()) || !env.getProperty("MERCADO_PAGO_MARKETPLACE_APPROVED",Boolean.class,false)))
            throw new IllegalStateException("Live collection requires HTTPS and approval of the marketplace financial operation.");
        if("production".equals(env.getProperty("NODE_ENV")) && !"production".equals(mode))
            throw new IllegalStateException("Test payments are forbidden in production.");
        return new MercadoPagoSettings(token,env.getProperty("MERCADO_PAGO_PUBLIC_KEY",""),secret,mode,origin,
            env.getProperty("PAYMENT_PROVIDER_TIMEOUT_MS",Integer.class,10000));
    }
    public boolean test() { return "test".equals(environment); }
    @Override public String toString() { return "MercadoPagoSettings[environment="+environment+", credentials=REDACTED]"; }
}
