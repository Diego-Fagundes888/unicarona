package com.unicarona.notification.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.template.AuthEmailTemplate;
import java.net.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ResendEmailProviderTest {
    private EmailSettings settings(){return new EmailSettings(new MockEnvironment().withProperty("EMAIL_PROVIDER","resend").withProperty("EMAIL_PROVIDER_API_KEY","re_synthetic_test_key_2026").withProperty("APP_ORIGIN","https://app.example.test").withProperty("EMAIL_FROM","UniCarona <onboarding@resend.dev>"));} // gitleaks:allow -- synthetic fixture, never used by production.
    private EmailProvider.Message message(){return new EmailProvider.Message(UUID.randomUUID(),"synthetic@example.test","verify_email","https://app.example.test/login.html#verificar?token="+"a".repeat(43),"<Ana>");}
    @Test void sendsMultipartAlternativesWithStableProviderKeyAndNoRedirects() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);var keys=new ArrayList<String>();var payloads=new ArrayList<String>();var json=new ObjectMapper();
        server.createContext("/emails",x->{keys.add(x.getRequestHeaders().getFirst("Idempotency-Key"));assertEquals("Bearer re_synthetic_test_key_2026",x.getRequestHeaders().getFirst("Authorization"));payloads.add(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] b="{\"id\":\"7df199cc-c696-4bf0-819f-c0799088d66a\"}".getBytes();x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});server.start();
        try{
            var provider=new ResendEmailProvider(settings(),json,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/emails"));var m=message();provider.send(m);provider.send(m);
            assertEquals(List.of("auth-email/"+m.id(),"auth-email/"+m.id()),keys);assertEquals(payloads.getFirst(),payloads.getLast());var p=json.readTree(payloads.getFirst());
            assertTrue(p.path("html").asText().contains("&lt;Ana&gt;"));assertFalse(p.path("html").asText().contains("Olá, <Ana>"));assertTrue(p.path("text").asText().contains("60 minutos"));assertTrue(p.path("html").asText().contains("Verificar meu e-mail"));assertTrue(p.path("text").asText().contains(m.url()));assertFalse(p.toString().contains("re_synthetic"));
        }finally{server.stop(0);}
    }
    @Test void upstreamSecretsAndBodiesNeverEscapeTheAdapter() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.createContext("/emails",x->{x.getRequestBody().readAllBytes();byte[] b="private-upstream-body".getBytes();x.sendResponseHeaders(401,b.length);x.getResponseBody().write(b);x.close();});server.start();
        try{var p=new ResendEmailProvider(settings(),new ObjectMapper(),HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/emails"));var error=assertThrows(ResendEmailProvider.DeliveryException.class,()->p.send(message()));assertEquals("EMAIL_PROVIDER_REJECTED",error.getMessage());assertNull(error.getCause());}finally{server.stop(0);}
    }
    @Test void templateRejectsForeignOrMalformedLinksAndFreezesBothAlternatives() {
        var t=new AuthEmailTemplate(settings());var m=message();var c=t.render(m);assertEquals("AuthEmailContent[redacted]",c.toString());
        assertThrows(IllegalArgumentException.class,()->t.render(new EmailProvider.Message(m.id(),m.to(),m.kind(),"https://evil.example/login.html#verificar?token="+"a".repeat(43))));
        assertThrows(IllegalArgumentException.class,()->t.render(new EmailProvider.Message(m.id(),m.to(),m.kind(),"https://app.example.test/login.html#verificar?token=short")));
        assertEquals(c,t.render(new EmailProvider.Message(m.id(),m.to(),m.kind(),m.url(),"Other name",c)));
    }
}
