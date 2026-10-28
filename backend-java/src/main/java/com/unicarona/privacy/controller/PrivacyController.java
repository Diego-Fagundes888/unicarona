package com.unicarona.privacy.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.privacy.dto.PrivacyDto;
import com.unicarona.privacy.service.PrivacyRightsService;
import com.unicarona.privacy.service.PrivacyExportService;
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

@RestController @RequestMapping("/api/v1/privacy") @Tag(name="Privacy") @SecurityRequirement(name="cookieAuth")
public class PrivacyController {
    private final PrivacyRightsService rights;private final PrivacyExportService exports;private final com.unicarona.privacy.service.PrivacyClosureService closures;
    public PrivacyController(PrivacyRightsService rights,PrivacyExportService exports,com.unicarona.privacy.service.PrivacyClosureService closures){this.rights=rights;this.exports=exports;this.closures=closures;}
    @GetMapping("/closure") public PrivacyDto.Closure closure(@AuthenticationPrincipal Actor actor){return closures.status(actor);}
    @PostMapping("/closure") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) public PrivacyDto.Closure close(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.ClosureRequest input,@RequestHeader(value="idempotency-key",required=false)String key){return closures.request(actor,input,key);}
    @PostMapping("/closure/cancel") public PrivacyDto.Closure cancelClosure(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.Reauthenticate input,@RequestHeader(value="idempotency-key",required=false)String key){return closures.cancel(actor,input,key);}
    @GetMapping("/preferences") public PrivacyDto.Preferences preferences(@AuthenticationPrincipal Actor actor){return rights.preferences(actor);}
    @PatchMapping("/preferences") public PrivacyDto.Preferences preferences(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.PreferenceUpdate input){return rights.updatePreferences(actor,input);}
    @PostMapping("/requests") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public PrivacyDto.Protocol request(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.Request input,@RequestHeader(value="idempotency-key",required=false) String key){return rights.request(actor,input,key);}
    @GetMapping("/requests") public PageDto<PrivacyDto.Protocol> requests(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query){var page=Pagination.parse(Pagination.singleValues(query),Set.of());return PageDto.from(rights.list(actor,page.limit()+1,page.offset()),page.limit(),page.offset());}
    @GetMapping("/requests/{id}") public PrivacyDto.Detail detail(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id){return rights.detail(actor,UUID.fromString(id));}
    @PostMapping("/export") public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> export(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrivacyDto.Reauthenticate input,jakarta.servlet.http.HttpServletRequest request){
        exports.authorize(actor,input.password());
        request.setAttribute("unicarona.authorizedPrivateDownload",Boolean.TRUE);
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body=output->exports.write(actor.id(),output);
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").header("Pragma","no-cache").header("Referrer-Policy","no-referrer").header("Content-Disposition","attachment; filename=unicarona-meus-dados.zip").contentType(org.springframework.http.MediaType.parseMediaType("application/zip")).body(body);
    }
}
