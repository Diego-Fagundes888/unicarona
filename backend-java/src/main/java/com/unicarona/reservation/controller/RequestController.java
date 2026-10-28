package com.unicarona.reservation.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.reservation.dto.*;
import com.unicarona.reservation.service.RequestService;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Requests") @SecurityRequirement(name="cookieAuth")
public class RequestController {
    private final RequestService service;
    public RequestController(RequestService service) { this.service=service; }
    @PostMapping("/api/v1/ride-requests") @Operation(summary="Solicitar avulsa ou agenda mensal sem ocupar vaga")
    public RequestDto.Created create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody RequestDto.Create input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.create(actor,input,key); }
    @GetMapping("/api/v1/ride-requests") @Operation(summary="Consultar pedidos próprios recebidos ou enviados com filtro de situação")
    public PageDto<RequestDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.list(actor,RequestQueries.parse(Pagination.singleValues(query))); }
    @GetMapping("/api/v1/ride-requests/{id}") @Operation(summary="Consultar detalhes e agenda congelada de solicitação própria")
    public RequestDto.Detail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.detail(actor,id); }
    @PostMapping("/api/v1/ride-requests/{id}/respond") @Operation(summary="Motorista aceitar ou recusar pedido pendente")
    public RequestDto.Responded respond(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody RequestDto.Respond input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.respond(actor,id,input.accept(),key); }
    @PostMapping("/api/v1/ride-requests/{id}/cancel") @Operation(summary="Cancelar pedido antes da confirmação da reserva")
    public RequestDto.Cancelled cancel(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody RequestDto.Empty input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.cancel(actor,id,key); }
}
