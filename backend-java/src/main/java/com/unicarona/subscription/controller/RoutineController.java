package com.unicarona.subscription.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import com.unicarona.subscription.dto.*;
import com.unicarona.subscription.service.RoutineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Subscriptions") @SecurityRequirement(name="cookieAuth")
public class RoutineController {
    private final RoutineService service;
    public RoutineController(RoutineService service) { this.service=service; }
    @PostMapping("/api/v1/routines") @Operation(summary="Publicar rotina com agenda e preço calculado")
    public SubscriptionDto.Created create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody SubscriptionDto.RoutineInput input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.create(actor,input,key); }
    @GetMapping("/api/v1/routines") @Operation(summary="Buscar rotinas da faculdade com compatibilidade de turma")
    public PageDto<SubscriptionDto.Routine> search(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.search(actor,RoutineQueries.parse(Pagination.singleValues(query))); }
    @GetMapping("/api/v1/routines/mine") @Operation(summary="Consultar rotinas próprias, inclusive pausadas")
    public PageDto<SubscriptionDto.Routine> mine(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.mine(actor,Pagination.parse(Pagination.singleValues(query),Set.of())); }
    @GetMapping("/api/v1/routines/{id}") @Operation(summary="Consultar agenda conforme vínculo ou oferta publicada")
    public SubscriptionDto.Routine detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.detail(actor,id); }
    @PatchMapping("/api/v1/routines/{id}/availability") @Operation(summary="Pausar novas adesões ou reabrir rotina")
    public SubscriptionDto.State availability(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody SubscriptionDto.Availability input) { return service.availability(actor,id,input.enabled()); }
    @PostMapping("/api/v1/subscriptions/preview") @Operation(summary="Calcular agenda proporcional e exclusões conhecidas")
    public SubscriptionDto.Preview preview(@AuthenticationPrincipal Actor actor,@Valid @RequestBody SubscriptionDto.MonthlyInput input) { return service.preview(actor,input); }
}
