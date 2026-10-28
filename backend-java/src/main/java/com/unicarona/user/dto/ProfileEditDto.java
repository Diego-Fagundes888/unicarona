package com.unicarona.user.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;

public final class ProfileEditDto {
    private ProfileEditDto() {}
    @Schema(name="ProfileUpdateInput") public record Update(@NotBlank @Size(min=2,max=120) String name,
        @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String phone,@NotNull @Valid Academic academic,@NotNull Boolean acknowledgeMonthlyImpact) {
        public Update {
            ApiException.require(name==null || name.length()<=120,"VALIDATION_ERROR","Confira os campos informados.",422);name=InputText.trim(name);
            if(phone!=null) { ApiException.require(phone.length()<=25,"VALIDATION_ERROR","Confira os campos informados.",422);phone=phone.replaceAll("\\D","");ApiException.require(phone.isEmpty() || phone.length()>=10 && phone.length()<=15,"VALIDATION_ERROR","Confira os campos informados.",422); }
        }
        public Map<String,Object> fingerprint() {
            var body=new LinkedHashMap<String,Object>();body.put("name",name);if(phone!=null) body.put("phone",phone);body.put("academic",academic.fingerprint());body.put("acknowledgeMonthlyImpact",acknowledgeMonthlyImpact);return body;
        }
    }
    /** Presence is retained for nullable optional fields because omitted and null have different legacy fingerprints. */
    @Schema(name="ProfileAcademicUpdate") public static class Academic {
        @JsonProperty @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) private String universityId;
        @JsonProperty @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) private String courseId;
        @JsonProperty @Pattern(regexp=AuthDto.UUID_PATTERN) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) private String regionId;
        @NotBlank @Size(max=100) private String region;
        @JsonProperty @NotNull @Pattern(regexp="numbered|irregular|undefined") private String periodStatus;
        @Min(1) @Max(100) private Integer periodNumber;
        @JsonProperty @NotNull @Pattern(regexp="morning|afternoon|night|full-time|variable") private String shift;
        @Pattern(regexp="A|B|C") private String classLabel;
        private boolean periodPresent,classPresent;
        @JsonSetter("region") public void region(String value) { ApiException.require(value==null || value.length()<=100,"VALIDATION_ERROR","Confira os campos informados.",422);region=InputText.trim(value); }
        @JsonSetter("periodNumber") @JsonDeserialize(using=NullableInteger.class) public void periodNumber(Integer value) { periodPresent=true;periodNumber=value; }
        @JsonSetter("classLabel") public void classLabel(String value) { classPresent=true;classLabel=value; }
        @AssertTrue @JsonIgnore @Schema(hidden=true) public boolean isNumberedValid() { return !"numbered".equals(periodStatus) || periodNumber!=null; }
        public String universityId() { return universityId; } public String courseId() { return courseId; }
        public String periodStatus() { return periodStatus; } public Integer periodNumber() { return periodNumber; } public String classLabel() { return classLabel; }
        public AuthDto.Academic input() { return new AuthDto.Academic(universityId,courseId,regionId,region,periodStatus,periodNumber,shift,classLabel); }
        public Map<String,Object> fingerprint() {
            var body=new LinkedHashMap<String,Object>();body.put("universityId",universityId);body.put("courseId",courseId);if(regionId!=null) body.put("regionId",regionId);body.put("region",region);body.put("periodStatus",periodStatus);if(periodPresent) body.put("periodNumber",periodNumber);body.put("shift",shift);if(classPresent) body.put("classLabel",classLabel);return body;
        }
    }
    public static class NullableInteger extends StdDeserializer<Integer> {
        public NullableInteger() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(!parser.currentToken().isNumeric()) throw JsonMappingException.from(parser,"Informe um inteiro válido.");try { return parser.getDecimalValue().intValueExact(); } catch(ArithmeticException error) { throw JsonMappingException.from(parser,"Informe um inteiro válido."); }
        }
    }
}
