package com.unicarona.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookAuthentication {
    private final String secret;
    private final ObjectMapper json;
    private final Clock clock;
    public WebhookAuthentication(String secret,ObjectMapper json,Clock clock) { this.secret=secret;this.json=json;this.clock=clock; }
    public String signature(String version,String timestamp,Object payload) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update(((version.isEmpty()?"":version+".")+timestamp+".").getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(payload)));
        } catch(Exception error) { throw new IllegalStateException("Cannot sign webhook payload."); }
    }
    public void authenticate(String version,Object payload,String timestamp,String signature) {
        boolean valid=timestamp!=null && timestamp.matches("[0-9]{10}") && signature!=null && signature.matches("[a-f0-9]{64}");
        if(valid) valid=Math.abs(clock.millis()/1000d-Long.parseLong(timestamp))<=300
            && MessageDigest.isEqual(HexFormat.of().parseHex(signature(version,timestamp,payload)),HexFormat.of().parseHex(signature));
        ApiException.require(valid,"WEBHOOK_UNAUTHORIZED","Assinatura do evento inválida.",401);
    }
}
