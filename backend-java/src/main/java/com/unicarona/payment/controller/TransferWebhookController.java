package com.unicarona.payment.controller;

import com.unicarona.payment.dto.TransferDto;
import com.unicarona.payment.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Payments")
public class TransferWebhookController {
    private final TransferService service;
    public TransferWebhookController(TransferService service) { this.service=service; }
    @PostMapping("/api/v1/webhooks/transfers") @Operation(summary="Resultado de saque ou devolução autenticado pelo provedor")
    public TransferDto.Received receive(@RequestBody @Valid TransferDto.Event event,
        @RequestHeader(value="x-webhook-timestamp",required=false) String timestamp,@RequestHeader(value="x-webhook-signature",required=false) String signature) {
        return service.receive(event,timestamp,signature);
    }
}
