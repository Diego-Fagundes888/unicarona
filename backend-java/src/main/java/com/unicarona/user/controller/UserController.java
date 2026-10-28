package com.unicarona.user.controller;

import com.unicarona.security.Actor;
import com.unicarona.user.dto.ProfileDto;
import com.unicarona.user.dto.ProfileEditDto;
import com.unicarona.user.service.ProfileService;
import jakarta.validation.Valid;
import com.unicarona.user.repository.ProfileRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/users") @Tag(name="Identity")
public class UserController {
    private final ProfileRepository profiles;
    private final ProfileService service;
    public UserController(ProfileRepository profiles,ProfileService service) { this.profiles=profiles;this.service=service; }
    @GetMapping("/me") @Operation(summary="Consultar dados próprios",security=@SecurityRequirement(name="cookieAuth"))
    public ProfileDto me(@AuthenticationPrincipal Actor actor) { return profiles.find(actor.id()); }
    @PatchMapping("/me") @Operation(summary="Editar perfil com confirmação do impacto nos mensais",security=@SecurityRequirement(name="cookieAuth"))
    public ProfileDto update(@AuthenticationPrincipal Actor actor,@Valid @RequestBody ProfileEditDto.Update input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.update(actor,input,key); }
}
