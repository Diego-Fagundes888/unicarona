package com.unicarona.payment.controller;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Payments")
public class PaymentWebhookController {
    private final PaymentService service;
    public PaymentWebhookController(PaymentService service) { this.service=service; }
    @PostMapping("/api/v1/webhooks/payments") @Operation(summary="Receber evento autenticado e idempotente do gateway configurado")
    public PaymentDto.Received receive(@Valid @RequestBody PaymentDto.Event event,@RequestHeader(name="x-webhook-timestamp",required=false,defaultValue="") String timestamp,@RequestHeader(name="x-webhook-signature",required=false,defaultValue="") String signature) { return service.receive(event,timestamp,signature); }
}
