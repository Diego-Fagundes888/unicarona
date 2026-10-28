package com.unicarona.ride.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.ride.dto.*;
import com.unicarona.ride.service.RideService;
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

@RestController @Tag(name="Rides") @SecurityRequirement(name="cookieAuth")
public class RideController {
    private final RideService service;
    public RideController(RideService service) { this.service=service; }
    @PostMapping("/api/v1/rides") @Operation(summary="Publicar carona com preço calculado no servidor")
    public RideDto.Created create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody RideDto.Create input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.create(actor,input,key); }
    @PatchMapping("/api/v1/rides/{id}") @Operation(summary="Editar oferta preservando preços contratados")
    public RideDto.Edited edit(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody RideDto.Edit input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.edit(actor,id,input,key); }
    @GetMapping("/api/v1/rides") @Operation(summary="Buscar caronas publicadas da faculdade do aluno")
    public PageDto<RideDto.Item> search(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.search(actor,RideQueries.search(Pagination.singleValues(query))); }
    @GetMapping("/api/v1/rides/mine") @Operation(summary="Listar ofertas próprias e histórico em ordem de saída")
    public PageDto<RideDto.Item> mine(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.mine(actor,RideQueries.mine(Pagination.singleValues(query))); }
    @GetMapping("/api/v1/rides/{id}") @Operation(summary="Consultar carona conforme participação e visibilidade")
    public RideDto.Item detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.detail(actor,id); }
}
