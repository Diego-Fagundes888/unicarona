package com.unicarona.payment.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.payment.service.*;
import com.unicarona.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/wallet/debt") @SecurityRequirement(name="cookieAuth")
@ConditionalOnProperty(name="PAYMENT_PROVIDER",havingValue="mercadopago")
public class DebtPaymentController {
    private final DebtPaymentService service;
    public DebtPaymentController(DebtPaymentService service){this.service=service;}
    @PostMapping("/checkout") @Operation(summary="Regularizar dívida própria via Pix, com valor calculado no servidor")
    public DebtPaymentTransactions.Detail checkout(@AuthenticationPrincipal Actor actor,@RequestHeader(name="idempotency-key",required=false) String key){return service.checkout(actor,key);}
    @GetMapping("/payments/{id}") @Operation(summary="Consultar pagamento próprio de regularização")
    public DebtPaymentTransactions.Detail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id){return service.detail(actor,UUID.fromString(id));}
    @PostMapping("/payments/{id}/sync") @Operation(summary="Verificar regularização própria na API oficial")
    public DebtPaymentTransactions.Detail sync(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id){return service.sync(actor,UUID.fromString(id));}
}
