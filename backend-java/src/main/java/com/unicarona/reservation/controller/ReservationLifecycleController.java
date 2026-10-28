package com.unicarona.reservation.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.auth.service.AuthService;
import com.unicarona.reservation.dto.LifecycleDto;
import com.unicarona.reservation.service.LifecycleService;
import com.unicarona.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Reservations") @SecurityRequirement(name="cookieAuth")
public class ReservationLifecycleController {
    private final LifecycleService service;
    private final AuthService auth;
    public ReservationLifecycleController(LifecycleService service,AuthService auth) { this.service=service;this.auth=auth; }
    @PostMapping("/api/v1/reservations/{id}/board") @Operation(summary="Validar PIN individual sem liberar valor")
    public LifecycleDto.Status board(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Board input,@RequestHeader(name="idempotency-key",required=false) String key) { auth.limit("boarding:"+actor.id()+":"+id,10,900);return service.board(actor,id,input.pin(),key); }
    @PostMapping("/api/v1/reservations/{id}/final") @Operation(summary="Passageiro confirmar conclusão e liberar sua parcela")
    public LifecycleDto.Status confirmFinal(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Empty input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.confirmFinal(actor,id,key); }
    @PostMapping("/api/v1/reservations/{id}/contest") @Operation(summary="Contestar somente a parcela envolvida")
    public LifecycleDto.Status contest(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Reason input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.contest(actor,id,input.reason(),key); }
    @PostMapping("/api/v1/reservations/{id}/cancel") @Operation(summary="Passageiro cancelar avulsa, com política financeira em análise")
    public LifecycleDto.Cancelled cancel(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Reason input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.cancelPassenger(actor,id,input.reason(),key); }
    @PostMapping("/api/v1/reservations/{id}/absence") @Operation(summary="Avisar ausência mensal mantendo vaga e valor")
    public LifecycleDto.Absence absence(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Reason input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.absence(actor,id,input.reason(),key); }
    @PostMapping("/api/v1/reservations/{id}/time-response") @Operation(summary="Responder proposta de horário; avulsa recusada recebe crédito integral")
    public LifecycleDto.Answer respondTime(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Response input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.respondTime(actor,id,input.accept(),key); }
}
