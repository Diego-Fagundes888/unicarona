package com.unicarona.privacy.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.privacy.dto.*;
import com.unicarona.privacy.service.AdminPrivacyHoldService;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminPrivacyHoldController {
    private final AdminPrivacyHoldService service;
    public AdminPrivacyHoldController(AdminPrivacyHoldService service){this.service=service;}
    @GetMapping("/api/v1/admin/privacy/holds") public PageDto<PrivacyHoldDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query){
        var values=Pagination.singleValues(query);var page=Pagination.parse(values,Set.of("userId","status"));String user=values.get("userId"),status=values.getOrDefault("status","");
        ApiException.require(user==null||user.matches(AuthDto.UUID_PATTERN),"VALIDATION_ERROR","Identificador de conta inválido.",422);
        ApiException.require(Set.of("","active","review_due","released","expired").contains(status),"VALIDATION_ERROR","Situação de retenção inválida.",422);
        return PageDto.from(service.list(actor,user==null?null:UUID.fromString(user),status,page.limit()+1,page.offset()),page.limit(),page.offset());
    }
    @PostMapping("/api/v1/admin/privacy/holds") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public PrivacyHoldDto.Item create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyHoldDto.Create input,@RequestHeader(value="idempotency-key",required=false)String key){return service.create(actor,input,key);}
    @PostMapping("/api/v1/admin/privacy/holds/{id}/review") public PrivacyHoldDto.Item review(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN)String id,@Valid @RequestBody PrivacyHoldDto.Review input,@RequestHeader(value="idempotency-key",required=false)String key){return service.review(actor,UUID.fromString(id),input,key);}
    @PostMapping("/api/v1/admin/privacy/holds/{id}/evidence") public PrivacyHoldDto.Evidence evidence(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN)String id,@Valid @RequestBody PrivacyDto.Reauthenticate input){return service.detail(actor,UUID.fromString(id),input);}
    @PostMapping("/api/v1/admin/privacy/holds/{id}/data") public org.springframework.http.ResponseEntity<byte[]> data(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN)String id,@Valid @RequestBody PrivacyDto.Reauthenticate input){var result=service.heldData(actor,UUID.fromString(id),input);return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).header("Content-Disposition","attachment; filename=\"evidencia-"+id+"."+result.extension()+"\"").contentType(org.springframework.http.MediaType.parseMediaType(result.contentType())).body(result.data());}
    @GetMapping("/api/v1/privacy/retentions") public PageDto<PrivacyHoldDto.OwnRetention> own(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query){var page=Pagination.parse(Pagination.singleValues(query),Set.of());return PageDto.from(service.own(actor,page.limit()+1,page.offset()),page.limit(),page.offset());}
}
