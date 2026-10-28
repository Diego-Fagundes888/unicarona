package com.unicarona.payment.provider;

import com.unicarona.auth.config.AuthSettings;
import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.shared.ApiException;

public final class DevelopmentPaymentProvider implements PaymentProvider {
    private final BillingSettings settings;
    private final AuthSettings auth;
    private final WebhookAuthentication authentication;
    public DevelopmentPaymentProvider(BillingSettings settings,AuthSettings auth,WebhookAuthentication authentication) { this.settings=settings;this.auth=auth;this.authentication=authentication; }
    @Override public String name() { return "development"; }
    @Override public Checkout createCheckout(Input input) {
        ApiException.require(!settings.production(),"PROVIDER_UNAVAILABLE","Gateway de testes indisponível.",503);
        return new Checkout("dev_"+input.paymentIdText(),auth.origin()+"/test-payment.html?payment="+input.paymentIdText(),input.expiresAt(),"test");
    }
    @Override public void authenticate(PaymentDto.Event event,String timestamp,String signature) { authentication.authenticate("",event,timestamp,signature); }
}
