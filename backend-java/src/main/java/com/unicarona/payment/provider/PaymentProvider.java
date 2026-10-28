package com.unicarona.payment.provider;

import com.unicarona.payment.dto.PaymentDto;
import java.util.UUID;

public interface PaymentProvider {
    record Input(UUID paymentId,int amountCents,String method,String expiresAt,String paymentIdText) {
        public Input(UUID paymentId,int amountCents,String method,String expiresAt) { this(paymentId,amountCents,method,expiresAt,paymentId.toString()); }
    }
    record Checkout(String reference,String url,String expiresAt,String environment) {}
    String name();
    Checkout createCheckout(Input input) throws Exception;
    void authenticate(PaymentDto.Event event,String timestamp,String signature);
}
