package com.unicarona.payment.controller;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.service.PaymentService;
import com.unicarona.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Payments") @SecurityRequirement(name="cookieAuth")
@ConditionalOnProperty(name="PAYMENT_PROVIDER",havingValue="development",matchIfMissing=true)
public class DevelopmentPaymentController {
    private final PaymentService service;
    public DevelopmentPaymentController(PaymentService service) { this.service=service; }
    @PostMapping("/api/v1/development/payments/{id}/result") @Operation(summary="Gateway local: produzir evento assinado para cobrança própria de teste")
    public PaymentDto.Received result(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody PaymentDto.ResultInput input) { return service.developmentResult(actor,id,input); }
}
