package com.unicarona.payment.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.payment.dto.MercadoPagoReviewDto;
import com.unicarona.payment.service.MercadoPagoReviewService;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/payment-reviews") @Tag(name="Admin") @SecurityRequirement(name="cookieAuth")
@PreAuthorize("hasRole('ADMIN')") @ConditionalOnProperty(name="PAYMENT_PROVIDER",havingValue="mercadopago")
public class MercadoPagoReviewController {
    private final MercadoPagoReviewService service;
    public MercadoPagoReviewController(MercadoPagoReviewService service) {this.service=service;}
    @GetMapping @Operation(summary="Listar cobranças que exigem revisão financeira sem expor dados de cartão")
    public PageDto<MercadoPagoReviewDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) {return service.list(actor,Pagination.parse(Pagination.singleValues(query),Set.of()));}
    @GetMapping("/{id}") @Operation(summary="Conferir recebimento, crédito e devoluções verificadas de um caso financeiro")
    public MercadoPagoReviewDto.Detail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) {return service.detail(actor,UUID.fromString(id));}
    @PostMapping("/{id}/recheck") @Operation(summary="Consultar novamente uma order em análise com motivo e auditoria; sem limpar bloqueios manualmente")
    public MercadoPagoReviewDto.Checked recheck(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody MercadoPagoReviewDto.Recheck input,@RequestHeader(name="idempotency-key",required=false) String key) {return service.recheck(actor,UUID.fromString(id),input,key);}
    @PostMapping("/{id}/resolve") @Operation(summary="Conciliar crédito de cancelamento com prova externa atualizada e histórico imutável")
    public MercadoPagoReviewDto.Resolved resolve(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody MercadoPagoReviewDto.Resolve input,@RequestHeader(name="idempotency-key",required=false) String key) {return service.resolve(actor,UUID.fromString(id),input,key);}
}
