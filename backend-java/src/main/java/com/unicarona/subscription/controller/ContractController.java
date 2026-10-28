package com.unicarona.subscription.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import com.unicarona.subscription.dto.ContractDto;
import com.unicarona.subscription.service.ContractService;
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
public class ContractController {
    private final ContractService service;
    public ContractController(ContractService service) { this.service=service; }
    @GetMapping("/api/v1/subscriptions") @Operation(summary="Mensais próprios e carência")
    public PageDto<ContractDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) {
        var page=Pagination.parse(Pagination.singleValues(query),Set.of("side"));String side=query.getFirst("side");if(side==null) side="passenger";
        ApiException.require(Set.of("passenger","driver").contains(side),"VALIDATION_ERROR","Confira os campos informados.",422);return service.list(actor,side,page.limit(),page.offset());
    }
    @GetMapping("/api/v1/subscriptions/{id}") @Operation(summary="Acompanhar contrato próprio")
    public ContractDto.Item detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.detail(actor,id); }
    @GetMapping("/api/v1/subscriptions/{id}/rights") @Operation(summary="Consultar direitos futuros do período próprio")
    public ContractDto.Rights rights(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.rights(actor,id); }
    @PostMapping("/api/v1/subscriptions/{id}/cancel-renewal") @Operation(summary="Desativar renovação preservando período pago")
    public ContractDto.Status cancelRenewal(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,
        @Valid @RequestBody ContractDto.Empty body,@RequestHeader(name="idempotency-key",required=false) String key) { return service.cancelRenewal(actor,id,key); }
    @PostMapping("/api/v1/subscriptions/{id}/remove") @Operation(summary="Motorista remover vínculo e creditar parcelas futuras")
    public ContractDto.Status remove(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,
        @Valid @RequestBody ContractDto.Remove body,@RequestHeader(name="idempotency-key",required=false) String key) { return service.remove(actor,id,body.reason(),key); }
    @PostMapping("/api/v1/waitlist") @Operation(summary="Registrar interesse compatível, sem reservar assento")
    public ContractDto.Status wait(@AuthenticationPrincipal Actor actor,@Valid @RequestBody ContractDto.Wait body,@RequestHeader(name="idempotency-key",required=false) String key) { return service.wait(actor,body.routineId(),key); }
    @GetMapping("/api/v1/waitlist") @Operation(summary="Consultar interesses próprios")
    public ContractDto.Interests interests(@AuthenticationPrincipal Actor actor) { return service.interests(actor); }
    @PostMapping("/api/v1/waitlist/{id}/leave") @Operation(summary="Retirar interesse próprio")
    public ContractDto.Status leave(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody ContractDto.Empty body) { return service.leave(actor,id); }
}
