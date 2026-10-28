package com.unicarona.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.provider.*;
import com.unicarona.shared.ApiException;
import com.unicarona.support.AuthTestSettings;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PaymentProviderTest {
    @Test void checkoutReviewDtoReadsItsCanonicalConstructorAndDistinguishesOmittedFromExplicitNull()throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var omitted=mapper.readValue("{\"method\":\"pix\",\"useCredit\":false}",PaymentDto.CheckoutInput.class);assertNull(omitted.tripReview());
        var reviewed=mapper.readValue("{\"method\":\"pix\",\"useCredit\":false,\"tripReview\":\""+"a".repeat(64)+"\"}",PaymentDto.CheckoutInput.class);assertEquals("a".repeat(64),reviewed.tripReview());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->mapper.readValue("{\"method\":\"pix\",\"useCredit\":false,\"tripReview\":null}",PaymentDto.CheckoutInput.class));
    }
    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("TOKEN_PEPPER",AuthTestSettings.PEPPER).withProperty("PIN_ENCRYPTION_KEY",AuthTestSettings.KEY)
            .withProperty("JWT_SECRET",AuthTestSettings.SECRET).withProperty("APP_ORIGIN",AuthTestSettings.ORIGIN).withProperty("PAYMENT_WEBHOOK_SECRET",AuthTestSettings.WEBHOOK);
    }
    @Test void checkoutKeepsTheExistingDevelopmentPageReferenceAndExpiry() {
        var env=environment();var auth=new AuthSettings(env);var settings=new BillingSettings(env);var gateway=new DevelopmentPaymentProvider(settings,auth,new WebhookAuthentication(settings.secret(),new ObjectMapper(),Clock.systemUTC()));
        UUID id=UUID.randomUUID();var input=new PaymentProvider.Input(id,12345,"pix","2026-10-03T12:00:00.000Z");
        var checkout=gateway.createCheckout(input);assertEquals("development",gateway.name());assertEquals("dev_"+id,checkout.reference());assertEquals(AuthTestSettings.ORIGIN+"/test-payment.html?payment="+id,checkout.url());
        assertEquals("test",checkout.environment());assertEquals(input.expiresAt(),checkout.expiresAt());assertEquals(checkout,gateway.createCheckout(input));
    }
    @Test void paymentSignatureMatchesNodeAndCannotBeReusedAsATransferSignature() {
        var env=environment();var settings=new BillingSettings(env);var signing=new WebhookAuthentication(settings.secret(),new ObjectMapper(),Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"),ZoneOffset.UTC));
        var gateway=new DevelopmentPaymentProvider(settings,new AuthSettings(env),signing);String timestamp="1790942400";
        var event=new PaymentDto.Event("evt_fixed","00000000-0000-4000-8000-000000000001","dev_fixed",12345,"approved");
        String expected="eebc2ba5619e749be3c7dcbc4f2cb92a1f793d1719428ca243759176a39d7357";assertEquals(expected,signing.signature("",timestamp,event));gateway.authenticate(event,timestamp,expected);
        assertEquals("WEBHOOK_UNAUTHORIZED",assertThrows(ApiException.class,() -> gateway.authenticate(event,timestamp,signing.signature("transfer-v1",timestamp,event))).code());
    }
    @Test void theDevelopmentGatewayCannotProduceAProductionCheckout() {
        var env=environment().withProperty("NODE_ENV","production").withProperty("APP_ORIGIN","https://synthetic.example.test")
            .withProperty("PAYMENT_PROVIDER","mercadopago").withProperty("PAYMENT_API_TOKEN","Synthetic provider token").withProperty("EMAIL_PROVIDER","smtp");
        var settings=new BillingSettings(env);var gateway=new DevelopmentPaymentProvider(settings,new AuthSettings(env),new WebhookAuthentication(settings.secret(),new ObjectMapper(),Clock.systemUTC()));
        assertEquals("PROVIDER_UNAVAILABLE",assertThrows(ApiException.class,() -> gateway.createCheckout(new PaymentProvider.Input(UUID.randomUUID(),100,"pix","2026-10-03T12:00:00.000Z"))).code());
    }
}
