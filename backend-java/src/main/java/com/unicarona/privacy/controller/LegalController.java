package com.unicarona.privacy.controller;

import com.unicarona.privacy.dto.LegalDto;
import com.unicarona.privacy.service.LegalDocumentService;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
public class LegalController {
    private final LegalDocumentService legal;
    public LegalController(LegalDocumentService legal){this.legal=legal;}
    @GetMapping("/api/v1/legal/documents") public LegalDto.Manifest manifest(){return legal.manifest();}
    @GetMapping("/api/v1/legal/documents/{slug}") public LegalDto.Document document(@PathVariable @Pattern(regexp=LegalDto.SLUG)String slug){return legal.document(slug,null);}
    @GetMapping("/api/v1/legal/documents/{slug}/{version}") public LegalDto.Document archive(@PathVariable @Pattern(regexp=LegalDto.SLUG)String slug,@PathVariable @Pattern(regexp="[A-Za-z0-9._-]{1,64}")String version){return legal.document(slug,version);}
    @GetMapping("/api/v1/privacy/legal") public LegalDto.Status status(@AuthenticationPrincipal Actor actor){return legal.status(actor);}
    @GetMapping("/api/v1/privacy/legal/history") public PageDto<LegalDto.Acceptance> history(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query){var page=Pagination.parse(Pagination.singleValues(query),Set.of());return PageDto.from(legal.history(actor.id(),page.limit()+1,page.offset()),page.limit(),page.offset());}
    @PostMapping("/api/v1/privacy/legal") public LegalDto.Status accept(@AuthenticationPrincipal Actor actor,@Valid @RequestBody LegalDto.Accept input,@RequestHeader(value="idempotency-key",required=false)String key){return legal.accept(actor,input,key);}
}
