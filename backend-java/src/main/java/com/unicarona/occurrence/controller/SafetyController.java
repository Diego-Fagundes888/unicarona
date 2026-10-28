package com.unicarona.occurrence.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.occurrence.dto.SafetyDto;
import com.unicarona.occurrence.service.SafetyService;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Safety") @SecurityRequirement(name="cookieAuth")
public class SafetyController {
    private final SafetyService service;
    public SafetyController(SafetyService service) { this.service=service; }
    @PostMapping("/api/v1/occurrences") @Operation(summary="Relatar antes, durante ou depois; acesso preservado em conta restrita")
    public SafetyDto.Status report(@AuthenticationPrincipal Actor actor,@Valid @RequestBody SafetyDto.Report input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.report(actor,input,key); }
    @GetMapping("/api/v1/occurrences") @Operation(summary="Acompanhar relatos próprios")
    public PageDto<SafetyDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { var p=page(query);return service.list(actor,false,p.limit(),p.offset()); }
    @PostMapping("/api/v1/blocks") @Operation(summary="Bloquear novas combinações e interromper mensal futuro para análise")
    public SafetyDto.Status block(@AuthenticationPrincipal Actor actor,@Valid @RequestBody SafetyDto.Block input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.block(actor,input,key); }
    @GetMapping("/api/v1/blocks") @Operation(summary="Listar bloqueios pessoais")
    public PageDto<SafetyDto.PersonalBlock> blocks(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { var p=page(query);return service.blocks(actor,p.limit(),p.offset()); }
    @PostMapping("/api/v1/blocks/{id}/remove") @Operation(summary="Remover bloqueio sem reativar vínculos encerrados")
    public SafetyDto.Removed unblock(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody SafetyDto.Empty body) { return service.unblock(actor,id); }
    @GetMapping("/api/v1/admin/occurrences") @Tag(name="Admin") @Operation(summary="Fila de ocorrências pendentes")
    public PageDto<SafetyDto.Item> adminList(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { var p=page(query);return service.list(actor,true,p.limit(),p.offset()); }
    @GetMapping("/api/v1/admin/occurrences/{id}") @Tag(name="Admin") @Operation(summary="Consultar trechos, valores congelados e decisões relacionadas")
    public SafetyDto.Detail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@RequestParam MultiValueMap<String,String> query) { var p=page(query);return service.detail(actor,id,p.limit(),p.offset()); }
    @PostMapping("/api/v1/admin/occurrences/{id}/review") @Tag(name="Admin") @Operation(summary="Resolver caso e reconhecer decisões divergentes")
    public SafetyDto.Reviewed review(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody SafetyDto.Review input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.review(actor,id,input,key); }
    private static Pagination page(MultiValueMap<String,String> query) { return Pagination.parse(Pagination.singleValues(query),Set.of()); }
}
