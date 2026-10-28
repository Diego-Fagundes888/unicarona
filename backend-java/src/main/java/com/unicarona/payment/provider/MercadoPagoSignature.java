package com.unicarona.payment.provider;

import com.unicarona.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Orders documentation uses lowercase IDs; the official simulator also signs their original case. */
public final class MercadoPagoSignature {
    private final String secret;
    private final Clock clock;
    public MercadoPagoSignature(String secret,Clock clock) { this.secret=secret;this.clock=clock; }
    public void verify(String dataId,String requestId,String signature) {
        try {
            if(dataId==null || !dataId.matches("ORD[A-Za-z0-9]{10,64}") || requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,128}") || signature==null || signature.length()>256) throw new IllegalArgumentException();
            var fields=new HashMap<String,String>();
            for(String part:signature.split(",")) { String[] pair=part.trim().split("=",2);if(pair.length!=2 || fields.put(pair[0],pair[1])!=null)throw new IllegalArgumentException(); }
            String ts=fields.get("ts"),digest=fields.get("v1");
            if(ts==null || !ts.matches("[0-9]{10}|[0-9]{13}") || digest==null || !digest.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException();
            long epoch=Long.parseLong(ts);if(ts.length()==10)epoch=Math.multiplyExact(epoch,1000);
            if(Math.abs(clock.millis()-epoch)>300000)throw new IllegalArgumentException();
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String manifest="id:"+dataId.toLowerCase(Locale.ROOT)+";request-id:"+requestId+";ts:"+ts+";";
            String original="id:"+dataId+";request-id:"+requestId+";ts:"+ts+";";
            byte[] expected=HexFormat.of().parseHex(digest);
            boolean normalized=MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)),expected);
            boolean exact=MessageDigest.isEqual(mac.doFinal(original.getBytes(StandardCharsets.UTF_8)),expected);
            // Both variants bind the same order, request ID and timestamp to the official secret.
            // Always evaluate both comparisons; neither bypasses the fresh provider lookup.
            if(!(normalized|exact))throw new IllegalArgumentException();
        } catch(Exception error) { throw new ApiException("WEBHOOK_UNAUTHORIZED","Assinatura do Mercado Pago inválida.",401); }
    }
}
