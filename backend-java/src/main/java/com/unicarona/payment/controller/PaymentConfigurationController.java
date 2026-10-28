package com.unicarona.payment.controller;

import com.unicarona.payment.config.BillingSettings;
import com.unicarona.payment.dto.MercadoPagoDto;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name="Payments")
public class PaymentConfigurationController {
    private final BillingSettings settings;
    private final Environment env;
    public PaymentConfigurationController(BillingSettings settings,Environment env) {this.settings=settings;this.env=env;}
    @GetMapping("/api/v1/payments/config")
    @Operation(summary="Configuração pública de checkout, sem Access Token ou segredo de webhook")
    public MercadoPagoDto.Configuration configuration() {
        boolean mp="mercadopago".equals(settings.provider());String publicKey=mp?env.getProperty("MERCADO_PAGO_PUBLIC_KEY",""):null;
        return new MercadoPagoDto.Configuration(settings.provider(),mp?env.getProperty("MERCADO_PAGO_ENVIRONMENT","test"):"test",publicKey,!mp || !publicKey.isBlank(),!mp,true);
    }
}
