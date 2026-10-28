package com.unicarona.admin.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.*;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.ride.dto.RideQueries;
import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.*;
import jakarta.validation.constraints.*;
import java.util.*;

public final class AdminCatalogDto {
    private AdminCatalogDto() {}
    @Schema(name="AdminPricingInput")
    @JsonPropertyOrder({"universityId","previousVersionId","fuelCents","efficiency","wearCents","referencePassengers","commissionBps","reason"})
    public record PricingInput(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String universityId,
        @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String previousVersionId,
        @NotNull @Min(10) @Max(10000) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer fuelCents,
        @NotNull @DecimalMin("1") @DecimalMax("100") @JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) Double efficiency,
        @NotNull @Min(0) @Max(2000) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer wearCents,
        @NotNull @Min(1) @Max(8) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer referencePassengers,
        @NotNull @Min(0) @Max(9500) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer commissionBps,
        @NotBlank @Size(min=3,max=1000) String reason) {
        public PricingInput { reason=text(reason,1000); }
        @JsonIgnore @Schema(hidden=true) @AssertTrue public boolean isEfficiencyPrecisionValid() { return efficiency==null || Math.abs(efficiency*1000-Math.round(efficiency*1000))<1e-7; }
    }
    @Schema(name="PricingVersion") public record Pricing(UUID id,UUID universityId,int fuelCents,@JsonSerialize(using=JavaScriptNumbers.DoubleSerializer.class) double efficiency,int wearCents,int referencePassengers,int commissionBps,int discountBps,String createdAt) {}
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,include=JsonTypeInfo.As.PROPERTY,property="scope")
    @JsonSubTypes({@JsonSubTypes.Type(value=UniversityExclusion.class,name="university"),@JsonSubTypes.Type(value=ClassExclusion.class,name="class")})
    @Schema(name="AdminCalendarInput",oneOf={UniversityExclusion.class,ClassExclusion.class},discriminatorProperty="scope",
        discriminatorMapping={@DiscriminatorMapping(value="university",schema=UniversityExclusion.class),@DiscriminatorMapping(value="class",schema=ClassExclusion.class)})
    public sealed interface CalendarInput permits UniversityExclusion,ClassExclusion {
        String universityId();String date();String title();
        @JsonIgnore default Map<String,Object> fingerprint() {
            var body=new LinkedHashMap<String,Object>();body.put("universityId",universityId());body.put("date",date());body.put("title",title());
            if(this instanceof ClassExclusion c) { body.put("scope","class");body.put("courseId",c.courseId());body.put("periodNumber",c.periodNumber());body.put("classLabel",c.classLabel()); }else body.put("scope","university");return body;
        }
    }
    @Schema(name="UniversityCalendarExclusionInput")
    public record UniversityExclusion(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String universityId,
        @NotNull String date,@NotBlank @Size(max=200) String title) implements CalendarInput {
        public UniversityExclusion { title=text(title,200); }
        @JsonIgnore @Schema(hidden=true) @AssertTrue public boolean isDateValid() { return RideQueries.validDate(date); }
    }
    @Schema(name="ClassCalendarExclusionInput")
    public record ClassExclusion(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String universityId,@NotNull String date,
        @NotBlank @Size(max=200) String title,@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String courseId,
        @NotNull @Min(1) @Max(100) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer periodNumber,
        @NotNull @Pattern(regexp="A|B|C") String classLabel) implements CalendarInput {
        public ClassExclusion { title=text(title,200); }
        @JsonIgnore @Schema(hidden=true) @AssertTrue public boolean isDateValid() { return RideQueries.validDate(date); }
    }
    @Schema(name="AdminCalendarDeactivateInput") public record Deactivate(@NotBlank @Size(min=3,max=1000) String reason) { public Deactivate { reason=text(reason,1000); } }
    @Schema(name="CalendarExclusion") public record Calendar(UUID id,UUID universityId,String date,String title,boolean active,UUID classId,String course,Integer periodNumber,String classLabel,String createdAt) {}
    @Schema(name="DeactivatedCalendarExclusion") public record Deactivated(String id,boolean active) {}
    static String text(String value,int maximum) { ApiException.require(value==null || value.length()<=maximum,"VALIDATION_ERROR","Confira os campos informados.",422);return InputText.trim(value); }
}
