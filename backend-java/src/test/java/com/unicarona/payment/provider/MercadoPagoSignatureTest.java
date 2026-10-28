package com.unicarona.payment.provider;

import com.unicarona.security.CryptoService;
import com.unicarona.shared.ApiException;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoSignatureTest {
    private final String secret="synthetic-webhook-secret-for-unit-tests";
    private final String id="ORD01M28P44G5FG8RJPM579EH56FV",request="request-1";
    private final Instant now=Instant.parse("2026-10-09T12:00:00Z");
    private final MercadoPagoSignature verifier=new MercadoPagoSignature(secret,Clock.fixed(now,ZoneOffset.UTC));
    private String signature(String timestamp) {
        String manifest="id:"+id.toLowerCase(java.util.Locale.ROOT)+";request-id:"+request+";ts:"+timestamp+";";
        return "ts="+timestamp+",v1="+new CryptoService().hmac(manifest,secret);
    }
    @Test void acceptsMillisecondsAndSecondsAndLowercasesAlphanumericIds() {
        verifier.verify(id,request,signature(Long.toString(now.toEpochMilli())));
        verifier.verify(id,request,signature(Long.toString(now.getEpochSecond())));
    }
    @Test void officialSimulatorOriginalCaseStillBindsTheOrderRequestTimestampAndSecret() {
        for(String timestamp:new String[]{Long.toString(now.toEpochMilli()),Long.toString(now.getEpochSecond())}) {
            String manifest="id:"+id+";request-id:"+request+";ts:"+timestamp+";";
            String signed="ts="+timestamp+",v1="+new CryptoService().hmac(manifest,secret);
            verifier.verify(id,request,signed);
            assertThrows(ApiException.class,()->verifier.verify(id+"A",request,signed));
            assertThrows(ApiException.class,()->verifier.verify(id,"request-2",signed));
            assertThrows(ApiException.class,()->new MercadoPagoSignature("wrong-secret",Clock.fixed(now,ZoneOffset.UTC)).verify(id,request,signed));
            assertThrows(ApiException.class,()->new MercadoPagoSignature(secret,Clock.fixed(now.plusSeconds(301),ZoneOffset.UTC)).verify(id,request,signed));
        }
    }
    @Test void rejectsReplayForgeryChangedIdentifierAndHeaderDuplicates() {
        assertThrows(ApiException.class,()->verifier.verify(id,request,signature(Long.toString(now.minusSeconds(301).toEpochMilli()))));
        assertThrows(ApiException.class,()->verifier.verify(id,"request-2",signature(Long.toString(now.toEpochMilli()))));
        assertThrows(ApiException.class,()->verifier.verify(id+"A",request,signature(Long.toString(now.toEpochMilli()))));
        assertThrows(ApiException.class,()->verifier.verify(id,request,signature(Long.toString(now.toEpochMilli()))+",ts=1"));
        assertThrows(ApiException.class,()->verifier.verify(id,request,"ts=1,v1="+"0".repeat(64)));
    }
    @Test void handlesMissingAndMalformedHeadersWithoutInternalErrors() {
        for(String signed:new String[]{null,"", "ts=invalid", "ts=1791547200000,v1=abc"})assertEquals(401,assertThrows(ApiException.class,()->verifier.verify(id,request,signed)).status());
        assertThrows(ApiException.class,()->verifier.verify(null,request,signature(Long.toString(now.toEpochMilli()))));
    }
}
