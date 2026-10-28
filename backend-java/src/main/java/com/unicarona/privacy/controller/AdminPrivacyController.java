package com.unicarona.privacy.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.privacy.service.AdminPrivacyService;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/admin/privacy/requests") @Tag(name="Privacy") @SecurityRequirement(name="cookieAuth")
public class AdminPrivacyController {
    private final AdminPrivacyService service;
    public AdminPrivacyController(AdminPrivacyService service){this.service=service;}
    @GetMapping public PageDto<PrivacyDto.AdminItem> queue(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query){
        var values=Pagination.singleValues(query);var page=Pagination.parse(values,Set.of("status"));String status=values.getOrDefault("status","");
        ApiException.require(Set.of("","received","in_review","awaiting_information","completed","denied").contains(status),"VALIDATION_ERROR","Confira os filtros informados.",422);
        return PageDto.from(service.list(actor,status,page.limit()+1,page.offset()),page.limit(),page.offset());
    }
    @GetMapping("/{id}") public PrivacyDto.AdminDetail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id){return service.detail(actor,UUID.fromString(id));}
    @PostMapping("/{id}/respond") public PrivacyDto.Protocol respond(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id,@Valid @RequestBody PrivacyDto.Review input,@RequestHeader(value="idempotency-key",required=false) String key){return service.respond(actor,UUID.fromString(id),input,key);}
}
