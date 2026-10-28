package com.unicarona.admin.controller;

import com.unicarona.admin.dto.*;
import com.unicarona.admin.service.AdminCatalogService;
import com.unicarona.auth.dto.AuthDto;
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

@RestController @Tag(name="Admin") @SecurityRequirement(name="cookieAuth")
public class AdminCatalogController {
    private final AdminCatalogService service;
    public AdminCatalogController(AdminCatalogService service) { this.service=service; }
    @GetMapping("/api/v1/admin/pricing") @Operation(summary="Parâmetros versionados da faculdade")
    public PageDto<AdminCatalogDto.Pricing> pricing(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.pricing(actor,AdminCatalogQueries.pricing(Pagination.singleValues(query))); }
    @PostMapping("/api/v1/admin/pricing") @Operation(summary="Criar versão sem alterar ofertas e parcelas contratadas")
    public AdminCatalogDto.Pricing pricing(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AdminCatalogDto.PricingInput input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.pricing(actor,input,key); }
    @GetMapping("/api/v1/admin/calendar") @Operation(summary="Calendário com escopo e histórico de desativação")
    public PageDto<AdminCatalogDto.Calendar> calendar(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.calendar(actor,AdminCatalogQueries.calendar(Pagination.singleValues(query))); }
    @PostMapping("/api/v1/admin/calendar") @Operation(summary="Excluir data de novas contratações preservando parcelas pagas")
    public AdminCatalogDto.Calendar calendar(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AdminCatalogDto.CalendarInput input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.calendar(actor,input,key); }
    @PostMapping("/api/v1/admin/calendar/{id}/deactivate") @Operation(summary="Desativar exclusão com justificativa e histórico")
    public AdminCatalogDto.Deactivated deactivate(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody AdminCatalogDto.Deactivate input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.deactivate(actor,id,input.reason(),key); }
}
