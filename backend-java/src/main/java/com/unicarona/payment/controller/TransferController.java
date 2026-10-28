package com.unicarona.payment.controller;

import com.unicarona.payment.dto.TransferDto;
import com.unicarona.payment.service.TransferService;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/transfers") @Tag(name="Payments") @SecurityRequirement(name="cookieAuth")
public class TransferController {
    private final TransferService service;
    public TransferController(TransferService service) { this.service=service; }
    @PostMapping @Operation(summary="Solicitar saque disponível ou devolução de crédito, sem liberar saldo futuro")
    public TransferDto.Created request(@AuthenticationPrincipal Actor actor,@RequestBody @Valid TransferDto.Request input,@RequestHeader(value="idempotency-key",required=false) String key) { return service.request(actor,input,key); }
    @GetMapping @Operation(summary="Histórico próprio de transferências")
    public PageDto<TransferDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) {
        var page=Pagination.parse(Pagination.singleValues(query),java.util.Set.of());return service.list(actor,page.limit(),page.offset());
    }
}
