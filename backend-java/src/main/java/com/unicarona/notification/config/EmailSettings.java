package com.unicarona.notification.config;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class EmailSettings {
    private final String provider,host,user,password,from,apiKey,frontendOrigin,supportUrl,privacyUrl;
    private final int verificationMinutes;
    private final int port;
    private final boolean secure;
    private final Path developmentPath,publicRoot;
    public EmailSettings(Environment env) {
        provider=env.getProperty("EMAIL_PROVIDER","development");host=env.getProperty("SMTP_HOST","");
        port=env.getProperty("SMTP_PORT",Integer.class,587);secure="true".equals(env.getProperty("SMTP_SECURE","false"));
        user=env.getProperty("SMTP_USER","");password=env.getProperty("SMTP_PASS","");from=env.getProperty("EMAIL_FROM",env.getProperty("MAIL_FROM","UniCarona <noreply@example.com>"));
        apiKey=env.getProperty("EMAIL_PROVIDER_API_KEY","");frontendOrigin=env.getProperty("APP_ORIGIN","http://127.0.0.1:3000");
        supportUrl=nonblank(env,"EMAIL_SUPPORT_URL",frontendOrigin+"/login.html#ajuda");
        privacyUrl=nonblank(env,"EMAIL_PRIVACY_URL",frontendOrigin+"/legal.html?document=privacy");
        verificationMinutes=env.getProperty("EMAIL_VERIFICATION_MINUTES",Integer.class,60);
        developmentPath=Path.of(env.getProperty("DEV_MAIL_PATH",".local/mail")).toAbsolutePath().normalize();
        publicRoot=Path.of(env.getProperty("FRONTEND_PATH","dist")).toAbsolutePath().normalize();
        if(!Set.of("development","smtp","resend").contains(provider) || !Set.of("true","false").contains(env.getProperty("SMTP_SECURE","false")) || verificationMinutes<5 || verificationMinutes>60 || port<1 || port>65535)
            throw new IllegalStateException("Invalid email provider configuration.");
        if("smtp".equals(provider) && host.isEmpty()) throw new IllegalStateException("SMTP_HOST is required.");
        if("resend".equals(provider) && !apiKey.matches("re_[A-Za-z0-9_-]{10,200}")) throw new IllegalStateException("Configure EMAIL_PROVIDER_API_KEY privately.");
        try {new jakarta.mail.internet.InternetAddress(from,true).validate();} catch(Exception error){throw new IllegalStateException("Invalid EMAIL_FROM.");}
        if(from.contains("\r")||from.contains("\n"))throw new IllegalStateException("Invalid EMAIL_FROM.");
        for(String link:java.util.List.of(supportUrl,privacyUrl)){
            var uri=java.net.URI.create(link);
            if(uri.getHost()==null || uri.getUserInfo()!=null || !("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&!"production".equals(env.getProperty("NODE_ENV")))))throw new IllegalStateException("Invalid email footer URL.");
        }
        if("production".equals(env.getProperty("NODE_ENV")) && "development".equals(provider)) throw new IllegalStateException("Development providers are forbidden in production.");
        if("production".equals(env.getProperty("NODE_ENV")) && (!from.contains("@")||from.contains("@resend.dev")||from.contains("@example.")||env.getProperty("EMAIL_SUPPORT_URL","").isBlank()||env.getProperty("EMAIL_PRIVACY_URL","").isBlank()))throw new IllegalStateException("Production requires a verified sender and configured support/privacy URLs.");
    }
    public String provider() { return provider; }
    public String host() { return host; }
    public int port() { return port; }
    public boolean secure() { return secure; }
    public String user() { return user; }
    public String password() { return password; }
    public String from() { return from; }
    public String apiKey() { return apiKey; }
    public String frontendOrigin() { return frontendOrigin; }
    public String supportUrl() { return supportUrl; }
    public String privacyUrl() { return privacyUrl; }
    public int verificationMinutes() { return verificationMinutes; }
    public Path developmentPath() { return developmentPath; }
    public Path publicRoot() { return publicRoot; }
    private static String nonblank(Environment env,String key,String fallback){String value=env.getProperty(key,"");return value.isBlank()?fallback:value;}
}
