package com.unicarona.reservation.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.reservation.dto.*;
import com.unicarona.reservation.service.ReservationReadService;
import com.unicarona.security.Actor;
import com.unicarona.shared.Pagination;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Reservations") @SecurityRequirement(name="cookieAuth")
public class ReservationReadController {
    private final ReservationReadService service;
    public ReservationReadController(ReservationReadService service) { this.service=service; }
    @GetMapping("/api/v1/reservations") @Operation(summary="Reservas e histórico próprios; PIN só para o passageiro")
    public PageDto<ReservationDto.Item> list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) { return service.list(actor,ReservationQueries.parse(Pagination.singleValues(query))); }
    @GetMapping("/api/v1/reservations/{id}") @Operation(summary="Acompanhar reserva própria e dados de embarque")
    public ReservationDto.Item detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.detail(actor,id); }
    @GetMapping("/api/v1/reservations/{id}/contact") @Operation(summary="Contato de participante confirmado, respeitando bloqueios")
    public ReservationDto.Contact contact(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) { return service.contact(actor,id); }
}
