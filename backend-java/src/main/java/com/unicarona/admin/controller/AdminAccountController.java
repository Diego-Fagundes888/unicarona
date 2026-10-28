package com.unicarona.admin.controller;

import com.unicarona.admin.dto.AdminAccountDto;
import com.unicarona.admin.service.AdminAccountService;
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
public class AdminAccountController {
    private final AdminAccountService service;
    public AdminAccountController(AdminAccountService service) { this.service=service; }
    @GetMapping("/api/v1/admin/accounts") @Operation(summary="Diretório privado de contas de estudantes para análise administrativa")
    public PageDto<AdminAccountDto.Account> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.list(actor,AdminAccountDto.query(Pagination.singleValues(query))); }
    @PostMapping("/api/v1/admin/accounts/{id}/review") @Operation(summary="Suspensão ou restauração com justificativa; finanças analisadas separadamente")
    public AdminAccountDto.Reviewed review(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody AdminAccountDto.Review input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.review(actor,id,input,key); }
}
