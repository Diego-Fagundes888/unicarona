package com.unicarona.privacy.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.privacy.dto.*;
import com.unicarona.privacy.service.AccountControlService;
import com.unicarona.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountControlController {
    private final AccountControlService service;
    public AccountControlController(AccountControlService service){this.service=service;}
    @GetMapping("/api/v1/privacy/account") public AccountControlDto.State state(@AuthenticationPrincipal Actor actor){return service.state(actor);}
    @PostMapping("/api/v1/privacy/account/deactivate") public AccountControlDto.State deactivate(@AuthenticationPrincipal Actor actor,@Valid @RequestBody AccountControlDto.Deactivate input,@RequestHeader(value="idempotency-key",required=false)String key){return service.deactivate(actor,input,key);}
    @PostMapping("/api/v1/privacy/account/reactivate") public AccountControlDto.State reactivate(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.Reauthenticate input,@RequestHeader(value="idempotency-key",required=false)String key){return service.reactivate(actor,input,key);}
    @GetMapping("/api/v1/admin/privacy/accounts/{id}") public AccountControlDto.AdminState detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN)String id){return service.detail(actor,UUID.fromString(id));}
    @PostMapping("/api/v1/admin/privacy/accounts/{id}/decision") public AccountControlDto.State decide(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN)String id,@Valid @RequestBody AccountControlDto.Decision input,@RequestHeader(value="idempotency-key",required=false)String key){return service.decide(actor,UUID.fromString(id),input,key);}
}
