package com.unicarona.notification.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.notification.dto.NotificationDto;
import com.unicarona.notification.service.NotificationService;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
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
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@SecurityRequirement(name = "cookieAuth")
public class NotificationController {
  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Notificações próprias, com paginação e total não lido")
  public NotificationDto.Page list(
      @AuthenticationPrincipal Actor actor, @RequestParam MultiValueMap<String, String> query) {
    var page = Pagination.parse(Pagination.singleValues(query), Set.of());
    return service.list(actor.id(), page.limit(), page.offset());
  }

  @PostMapping("/{id}/read")
  @Operation(summary = "Marcar aviso próprio como lido")
  public NotificationDto.Read read(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id,
      @Valid @RequestBody AuthDto.Empty body) {
    return service.read(actor.id(), UUID.fromString(id));
  }
}
