package com.unicarona.payment.controller;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.service.PaymentService;
import com.unicarona.security.Actor;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Payments") @SecurityRequirement(name="cookieAuth")
public class PaymentController {
    private final PaymentService service;
    public PaymentController(PaymentService service) { this.service=service; }
    @PostMapping("/api/v1/payments/{id}/checkout") @Operation(summary="Preparar cobrança com crédito opcional; navegador não aprova pagamento")
    @ApiResponse(responseCode="200",description="Cobrança própria após preparação ou confirmação por crédito integral",content=@Content(mediaType="application/json",schema=@Schema(implementation=PaymentDto.Detail.class)))
    public PaymentDto.Detail checkout(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody PaymentDto.CheckoutInput input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.checkout(actor,id,input,key); }
    @GetMapping("/api/v1/payments/{id}") @Operation(summary="Consultar cobrança própria")
    @ApiResponse(responseCode="200",description="Estado atual da cobrança própria",content=@Content(mediaType="application/json",schema=@Schema(implementation=PaymentDto.Detail.class)))
    public PaymentDto.Detail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.detail(actor,id); }
    @GetMapping("/api/v1/payments/{id}/summary")
    @Operation(summary="Consultar composição de preço congelada da cobrança própria")
    public PaymentDto.Summary summary(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) {return service.summary(actor,id);}
}
