package com.unicarona.student.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import com.unicarona.student.dto.IdentityDto;
import com.unicarona.student.service.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Identity")
@SecurityRequirement(name = "cookieAuth")
public class IdentityController {
  private final IdentityService service;

  public IdentityController(IdentityService service) {
    this.service = service;
  }

  @PatchMapping("/api/v1/users/me/settings")
  @Operation(summary = "Preferências próprias; avisos essenciais permanecem ativos")
  public IdentityDto.Settings settings(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody IdentityDto.Settings body) {
    return service.settings(actor, body);
  }

  @PostMapping("/api/v1/users/me/academic-verification")
  @Operation(summary = "Solicitar validação acadêmica administrativa")
  public IdentityDto.Verification request(
      @AuthenticationPrincipal Actor actor, @Valid @RequestBody AuthDto.Empty body) {
    return service.requestVerification(actor);
  }

  @GetMapping("/api/v1/admin/academic-verifications")
  @Operation(summary = "Fila administrativa de vínculo acadêmico")
  public PageDto<IdentityDto.QueueItem> queue(@RequestParam MultiValueMap<String, String> query) {
    var page = Pagination.parse(Pagination.singleValues(query), Set.of());
    return service.queue(page.limit(), page.offset());
  }

  @PostMapping("/api/v1/admin/academic-verifications/{id}/review")
  @Operation(summary = "Registrar decisão acadêmica administrativa")
  public IdentityDto.Decision review(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id,
      @Valid @RequestBody IdentityDto.Review body) {
    return service.review(actor, UUID.fromString(id), body);
  }
}
