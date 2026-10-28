package com.unicarona.job.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.job.dto.JobDto;
import com.unicarona.job.service.JobService;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.Pagination;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Admin") @SecurityRequirement(name="cookieAuth")
public class JobController {
    private final JobService service;
    public JobController(JobService service) { this.service=service; }
    @GetMapping("/api/v1/admin/jobs") @Operation(summary="Fila de envios com contagens e metadados; sem destinatários, links ou payloads")
    public JobDto.Page list(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query) {
        var page=Pagination.parse(Pagination.singleValues(query),Set.of("status"));String status=query.getFirst("status");if(status==null) status="dead";
        ApiException.require(Set.of("pending","processing","done","dead").contains(status),"VALIDATION_ERROR","Confira os campos informados.",422);
        return service.list(actor,status,page.limit(),page.offset());
    }
    @PostMapping("/api/v1/admin/jobs/{id}/retry") @Operation(summary="Retomar tarefa esgotada com justificativa e controle de versão; preserva identidade e tentativas")
    public JobDto.Retried retry(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,
        @Valid @RequestBody JobDto.Retry input,@RequestHeader(name="idempotency-key",required=false) String key) { return service.retry(actor,id,input,key); }
}
