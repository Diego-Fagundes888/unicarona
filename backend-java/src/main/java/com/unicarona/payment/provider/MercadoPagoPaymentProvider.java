package com.unicarona.payment.provider;

import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.service.MercadoPagoService;
import com.unicarona.shared.ApiException;

public final class MercadoPagoPaymentProvider implements PaymentProvider {
    private final MercadoPagoService service;
    public MercadoPagoPaymentProvider(MercadoPagoService service) { this.service=service; }
    @Override public String name() { return "mercadopago"; }
    @Override public Checkout createCheckout(Input input) throws Exception {
        ApiException.require("pix".equals(input.method()),"CARD_TOKEN_REQUIRED","Use o formulário seguro para pagar com cartão.",422);
        return service.create(input);
    }
    @Override public void authenticate(PaymentDto.Event event,String timestamp,String signature) {
        throw new ApiException("WEBHOOK_UNAUTHORIZED","Utilize a notificação oficial de Orders do Mercado Pago.",401);
    }
}
