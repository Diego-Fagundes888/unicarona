package com.unicarona.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.TransferDto;
import com.unicarona.payment.provider.WebhookAuthentication;
import com.unicarona.shared.ApiException;
import com.unicarona.support.AuthTestSettings;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class WebhookAuthenticationTest {
    private final TransferDto.Event event=new TransferDto.Event("evt_fixed","00000000-0000-4000-8000-000000000001","dev_transfer_fixed",12345,"approved");
    private final String timestamp="1790942400";
    private WebhookAuthentication auth(Instant now) { return new WebhookAuthentication(AuthTestSettings.WEBHOOK,new ObjectMapper(),Clock.fixed(now,ZoneOffset.UTC)); }
    @Test void transferHmacMatchesTheOriginalNodeImplementationIncludingPropertyOrder() {
        var auth=auth(Instant.parse("2026-10-02T12:00:00Z"));
        assertEquals("fe755da214cb9d379a46b85c1dfcb2fa141b1a274beaad4f21056c8c6cff167b",auth.signature("transfer-v1",timestamp,event));
        auth.authenticate("transfer-v1",event,timestamp,auth.signature("transfer-v1",timestamp,event));
        var normalized=new TransferDto.Event("\uFEFF evt_fixed \u00A0",event.transferId()," dev_transfer_fixed ",12345,"approved");
        assertEquals(auth.signature("transfer-v1",timestamp,event),auth.signature("transfer-v1",timestamp,normalized));
    }
    @Test void signatureAndTimestampRejectTamperingAndTheExactFiveMinuteBoundaryIsPreserved() {
        Instant instant=Instant.parse("2026-10-02T12:00:00Z");var auth=auth(instant);String signature=auth.signature("transfer-v1",timestamp,event);
        auth(instant.plusSeconds(300)).authenticate("transfer-v1",event,timestamp,signature);
        assertEquals("WEBHOOK_UNAUTHORIZED",assertThrows(ApiException.class,() -> auth(instant.plusMillis(300001)).authenticate("transfer-v1",event,timestamp,signature)).code());
        assertEquals("WEBHOOK_UNAUTHORIZED",assertThrows(ApiException.class,() -> auth.authenticate("transfer-v1",event,timestamp,signature.toUpperCase())).code());
        assertEquals("WEBHOOK_UNAUTHORIZED",assertThrows(ApiException.class,() -> auth.authenticate("transfer-v1",event,"bad",signature)).code());
        assertEquals("WEBHOOK_UNAUTHORIZED",assertThrows(ApiException.class,() -> auth.authenticate("payment-v1",event,timestamp,signature)).code());
    }
    @Test void invalidProviderConfigurationAndProductionDevelopmentAreRejectedWithoutExposingValues() {
        var env=new MockEnvironment().withProperty("PAYMENT_WEBHOOK_SECRET",AuthTestSettings.WEBHOOK);
        assertEquals(10000,new BillingSettings(env).timeoutMs());env.withProperty("PAYMENT_PROVIDER_TIMEOUT_MS","99");
        assertThrows(IllegalStateException.class,() -> new BillingSettings(env));env.withProperty("PAYMENT_PROVIDER_TIMEOUT_MS","10000").withProperty("NODE_ENV","production");
        var error=assertThrows(IllegalStateException.class,() -> new BillingSettings(env));assertFalse(error.getMessage().contains(AuthTestSettings.WEBHOOK));
    }
}
