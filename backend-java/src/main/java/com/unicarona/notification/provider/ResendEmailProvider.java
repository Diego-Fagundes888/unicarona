package com.unicarona.notification.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.notification.config.EmailSettings;
import com.unicarona.notification.template.AuthEmailTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Fixed HTTPS destination; never log upstream bodies or authorization headers. */
public final class ResendEmailProvider implements EmailProvider {
    private final EmailSettings settings;
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI endpoint;
    private final AuthEmailTemplate template;
    public ResendEmailProvider(EmailSettings settings,ObjectMapper json) {
        this(settings,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("https://api.resend.com/emails"));
    }
    // Package-private seam: loopback contract tests only, never controlled by a request/environment URL.
    ResendEmailProvider(EmailSettings settings,ObjectMapper json,HttpClient client,URI endpoint) {
        this.settings=settings;this.json=json;this.client=client;this.endpoint=endpoint;this.template=new AuthEmailTemplate(settings);
    }
    @Override public void send(Message input) throws Exception {
        var content=template.render(input);
        var body=new LinkedHashMap<String,Object>();
        body.put("from",settings.from());body.put("to",List.of(input.to()));body.put("subject",content.subject());
        body.put("html",content.html());body.put("text",content.text());
        // The same job always carries the same content and provider identity, including after a timeout.
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
            .header("Authorization","Bearer "+settings.apiKey()).header("Content-Type","application/json")
            .header("Idempotency-Key","auth-email/"+input.id()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try {
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(16385);
                if(response.statusCode()<200||response.statusCode()>=300||bytes.length>16384)throw new DeliveryException("EMAIL_PROVIDER_REJECTED");
                var result=json.readTree(bytes);
                if(!result.path("id").asText().matches("[A-Za-z0-9_-]{8,128}"))throw new DeliveryException("EMAIL_PROVIDER_RESPONSE_INVALID");
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new DeliveryException("EMAIL_DELIVERY_INTERRUPTED");}
        catch(DeliveryException error){throw error;}
        catch(Exception error){throw new DeliveryException("EMAIL_PROVIDER_UNAVAILABLE");}
    }
    public static final class DeliveryException extends Exception {
        public DeliveryException(String code){super(code);}
    }
}
