package com.unicarona.privacy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class LegalDto {
    private LegalDto(){}
    public static final String SLUG="terms|privacy|driver-terms|participation|payments|safety";
    public record Reference(@NotBlank @Pattern(regexp=SLUG)String slug,
        @NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,64}")String version,
        @NotBlank @Pattern(regexp="[a-f0-9]{64}")String contentSha256){}
    public record Document(Reference reference,String title,String kind,List<String> requiredFor,boolean materialChange,
        String changeSummary,String body,String publishedAt,String effectiveAt){public String href(){return "/legal.html?document="+reference.slug()+"&version="+reference.version();}}
    public record Manifest(List<Document> documents){}
    public record Acceptance(Reference document,String kind,String source,String acceptedAt){}
    public record Status(List<Reference> required,List<Reference> driverRequired,List<Acceptance> accepted){}
    public record Accept(@NotNull @AssertTrue Boolean terms,@NotNull @AssertTrue Boolean privacyNotice,
        Boolean driverTerms,@NotNull @Size(min=5,max=6)List<@Valid Reference> documents){}
    public record Registration(@NotNull @AssertTrue Boolean privacyNotice,
        @NotNull @Size(min=5,max=5)List<@Valid Reference> documents){}
}
