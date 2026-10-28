package com.unicarona.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.payment.dto.MercadoPagoDto;
import com.unicarona.payment.dto.PaymentDto;
import com.unicarona.payment.service.MercadoPagoService;
import com.unicarona.payment.service.PaymentService;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Payments")
@ConditionalOnProperty(name = "PAYMENT_PROVIDER", havingValue = "mercadopago")
public class MercadoPagoController {
  private final MercadoPagoService service;
  private final PaymentService payments;

  public MercadoPagoController(MercadoPagoService service, PaymentService payments) {
    this.service = service;
    this.payments = payments;
  }

  @PostMapping("/api/v1/payments/{id}/card")
  @Operation(
      summary =
          "Enviar token oficial de cartão para cobrança própria com preço congelado no servidor")
  @SecurityRequirement(name = "cookieAuth")
  public PaymentDto.Detail card(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id,
      @Valid @RequestBody MercadoPagoDto.CardInput input,
      @RequestHeader(name = "idempotency-key", required = false) String key) {
    service.card(actor, UUID.fromString(id), input, key);
    return payments.detail(actor, id);
  }

  @GetMapping("/api/v1/wallet/refund-review")
  @Operation(summary = "Consultar bloqueio do próprio crédito por devolução externa em análise")
  @SecurityRequirement(name = "cookieAuth")
  public MercadoPagoDto.RefundReview refundReview(@AuthenticationPrincipal Actor actor) {
    return service.refundReview(actor);
  }

  @GetMapping("/api/v1/transfers/{id}/refunds")
  @Operation(
      summary =
          "Conferir valores devolvidos, restaurados e em processamento de uma devolução própria")
  @SecurityRequirement(name = "cookieAuth")
  public MercadoPagoDto.RefundDetail refunds(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id) {
    return service.refunds(actor, UUID.fromString(id));
  }

  @PostMapping("/api/v1/payments/{id}/sync")
  @Operation(summary = "Consultar order própria no provedor ou retomar o mesmo comando de cartão")
  @SecurityRequirement(name = "cookieAuth")
  public MercadoPagoDto.GatewayDetail sync(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id) {
    return service.sync(actor, UUID.fromString(id));
  }

  @PostMapping("/api/v1/webhooks/mercadopago")
  @Operation(
      summary = "Validar assinatura Mercado Pago e consultar a order antes de atualizar o negócio")
  public PaymentDto.Received webhook(
      @RequestParam("data.id") String dataId,
      @RequestBody JsonNode body,
      @RequestHeader(value = "x-request-id", defaultValue = "") String requestId,
      @RequestHeader(value = "x-signature", defaultValue = "") String signature)
      throws Exception {
    ApiException.require(body.isObject(), "WEBHOOK_INVALID", "Notificação inválida.", 422);
    return service.webhook(
        dataId,
        requestId,
        signature,
        body.path("data").path("id").asText(),
        body.path("type").asText());
  }

  @GetMapping("/api/v1/payments/{id}/gateway")
  @Operation(summary = "Consultar estado externo verificado e instruções de pagamento próprias")
  @SecurityRequirement(name = "cookieAuth")
  public MercadoPagoDto.GatewayDetail detail(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id) {
    return service.detail(actor, UUID.fromString(id));
  }
}
