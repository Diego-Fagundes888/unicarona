package com.unicarona.ride.controller;

import com.unicarona.auth.dto.AuthDto;
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

@RestController @Tag(name="Rides") @SecurityRequirement(name="cookieAuth")
public class RideLifecycleController {
    private final LifecycleService service;
    public RideLifecycleController(LifecycleService service) { this.service=service; }
    @PostMapping("/api/v1/rides/{id}/start") @Operation(summary="Motorista iniciar trecho de hoje")
    public LifecycleDto.Status start(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Empty input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.start(actor,id,key); }
    @PostMapping("/api/v1/rides/{id}/finish") @Operation(summary="Declarar conclusão e disponibilidade mensal; abrir janela de 24 horas")
    public LifecycleDto.Status finish(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Finish input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.finish(actor,id,input.availability(),key); }
    @PostMapping("/api/v1/rides/{id}/cancel") @Operation(summary="Cancelar trecho próprio com crédito integral das parcelas pagas")
    public LifecycleDto.Status cancel(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Reason input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.cancelRide(actor,id,input.reason(),key); }
    @PostMapping("/api/v1/rides/{id}/no-class") @Operation(summary="Sem aula posterior ao contrato mensal; confirmação ou contestação separada")
    public LifecycleDto.Status noClass(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Reason input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.noClass(actor,id,input.reason(),key); }
    @PostMapping("/api/v1/rides/{id}/time-proposal") @Operation(summary="Propor alteração coletiva que exige consenso")
    public LifecycleDto.Status propose(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Proposal input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.proposeTime(actor,id,input.time(),input.reason(),key); }
    @PostMapping("/api/v1/rides/{id}/keep-time") @Operation(summary="Preservar horário original e retirar proposta")
    public LifecycleDto.Time keepTime(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody LifecycleDto.Empty input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.keepTime(actor,id,key); }
}
