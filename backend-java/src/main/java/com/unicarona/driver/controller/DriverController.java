package com.unicarona.driver.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.driver.dto.DriverDto;
import com.unicarona.driver.service.DriverService;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Drivers")
@SecurityRequirement(name = "cookieAuth")
public class DriverController {
  private final DriverService service;

  public DriverController(DriverService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/drivers/applications")
  @Operation(summary = "Solicitar análise com documentos privados")
  public DriverDto.Status apply(
      @AuthenticationPrincipal Actor actor,
      @Valid @RequestBody DriverDto.Apply body,
      @RequestHeader(name = "idempotency-key", required = false) String key) {
    return service.apply(actor, body, key);
  }

  @GetMapping({"/api/v1/drivers/applications", "/api/v1/admin/drivers/applications"})
  @Operation(summary = "Consultar solicitações de motorista com paginação")
  public PageDto<DriverDto.Application> applications(
      @AuthenticationPrincipal Actor actor, @RequestParam MultiValueMap<String, String> query) {
    var page = Pagination.parse(Pagination.singleValues(query), Set.of("status"));
    String status = query.getFirst("status");
    ApiException.require(
        status == null
            || Set.of("pending", "approved", "rejected", "replaced", "expired").contains(status),
        "VALIDATION_ERROR",
        "Confira os campos informados.",
        422);
    return service.applications(actor, page.limit(), page.offset(), status);
  }

  @PostMapping("/api/v1/admin/drivers/applications/{id}/review")
  @Operation(summary = "Aprovar ou recusar motorista com trilha de decisão")
  public DriverDto.Status review(
      @AuthenticationPrincipal Actor actor,
      @PathVariable @Pattern(regexp = AuthDto.UUID_PATTERN) String id,
      @Valid @RequestBody DriverDto.Review body,
      @RequestHeader(name = "idempotency-key", required = false) String key) {
    return service.review(actor, id, body, key);
  }
}
