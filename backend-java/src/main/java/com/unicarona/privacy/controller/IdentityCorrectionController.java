package com.unicarona.privacy.controller;

import com.unicarona.privacy.dto.*;
import com.unicarona.privacy.service.IdentityCorrectionService;
import com.unicarona.security.Actor;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/privacy/identity")
public class IdentityCorrectionController {
    private final IdentityCorrectionService service;
    public IdentityCorrectionController(IdentityCorrectionService service){this.service=service;}
    @GetMapping public IdentityCorrectionDto.State state(@AuthenticationPrincipal Actor actor){return service.state(actor);}
    @PostMapping("/email") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public IdentityCorrectionDto.Accepted request(@AuthenticationPrincipal Actor actor,@Valid @RequestBody IdentityCorrectionDto.EmailRequest input,@RequestHeader(value="idempotency-key",required=false)String key){return service.request(actor,input,key);}
    @PostMapping("/email/confirm") public IdentityCorrectionDto.Completed confirm(@AuthenticationPrincipal Actor actor,@Valid @RequestBody IdentityCorrectionDto.EmailConfirm input,@RequestHeader(value="idempotency-key",required=false)String key){return service.confirm(actor,input,key);}
    @PostMapping("/email/cancel") public IdentityCorrectionDto.Completed cancel(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.Reauthenticate input,@RequestHeader(value="idempotency-key",required=false)String key){return service.cancel(actor,input,key);}
    @PostMapping("/birth") public IdentityCorrectionDto.Completed birth(@AuthenticationPrincipal Actor actor,@Valid @RequestBody IdentityCorrectionDto.Birth input,@RequestHeader(value="idempotency-key",required=false)String key){return service.birth(actor,input,key);}
}
