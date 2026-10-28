package com.unicarona.occurrence.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;

public final class SafetyDto {
    private SafetyDto() {}
    @Schema(name="SafetyReportInput")
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonPropertyOrder({"otherId","reservationId","reason","severe","block"})
    public record Report(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String otherId,
        @Pattern(regexp=AuthDto.UUID_PATTERN) @JsonDeserialize(using=OptionalNonNullStringDeserializer.class) String reservationId,
        @NotBlank @Size(min=3,max=2000) String reason,@NotNull Boolean severe,@NotNull Boolean block) {
        public Report { reason=SafetyDto.reason(reason); }
    }
    @Schema(name="PersonalBlockInput") public record Block(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String otherId,
        @NotBlank @Size(min=3,max=2000) String reason) { public Block { reason=SafetyDto.reason(reason); } }
    @Schema(name="SafetyEmptyInput") public record Empty() {}
    @Schema(name="OccurrenceReviewInput") @JsonInclude(JsonInclude.Include.NON_NULL) @JsonPropertyOrder({"outcome","reason","acknowledgedDecisionIds"})
    public record Review(@NotNull @Pattern(regexp="released|credited|retained|no_financial_change") String outcome,
        @NotBlank @Size(min=3,max=2000) String reason,
        @Size(max=1000) @JsonDeserialize(using=DecisionsDeserializer.class) List<@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String> acknowledgedDecisionIds) {
        public Review { reason=SafetyDto.reason(reason);if(acknowledgedDecisionIds!=null) acknowledgedDecisionIds=List.copyOf(acknowledgedDecisionIds); }
    }
    private static String reason(String value) {
        ApiException.require(value==null || value.length()<=2000,"VALIDATION_ERROR","Confira os campos informados.",422);return InputText.trim(value);
    }
    /** Omission is optional; an explicitly null list or non-string member is invalid. */
    public static class DecisionsDeserializer extends StdDeserializer<List<String>> {
        public DecisionsDeserializer() { super(List.class); }
        @Override public List<String> deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(!parser.hasToken(JsonToken.START_ARRAY)) throw JsonMappingException.from(parser,"Informe as decisões relacionadas.");
            var result=new ArrayList<String>();while(parser.nextToken()!=JsonToken.END_ARRAY) {
                if(!parser.hasToken(JsonToken.VALUE_STRING) || result.size()>=1000) throw JsonMappingException.from(parser,"Informe as decisões relacionadas.");result.add(parser.getText());
            }return List.copyOf(result);
        }
        @Override public List<String> getNullValue(DeserializationContext context) throws JsonMappingException { throw JsonMappingException.from(context.getParser(),"Este campo não aceita null."); }
        @Override public List<String> getAbsentValue(DeserializationContext context) { return null; }
    }
    @Schema(name="SafetyStatus") public record Status(String id,String status) {}
    @Schema(name="RemovedPersonalBlock") public record Removed(String id,boolean active) {}
    @Schema(name="ReviewedOccurrence") public record Reviewed(String id,String status,int pendingOtherCases) {}
    @Schema(name="PersonalBlock") public record PersonalBlock(UUID id,UUID otherId,String name,String createdAt) {}
    @Schema(name="OccurrenceParticipant") public record Person(UUID id,String name) {}
    @Schema(name="OccurrenceParticipants") public record Participants(Person reporter,Person other) {}
    @Schema(name="OccurrenceItem") public record Item(UUID id,String kind,String reason,boolean severe,String status,String outcome,String decision,
        String createdAt,String reviewedAt,UUID reservationId,long affectedParcels,@JsonInclude(JsonInclude.Include.NON_NULL) Participants participants) {}
    @Schema(name="RelatedOccurrence") public record Related(UUID id,String kind,String status,String reason,String outcome,String decision,String reviewedAt) {}
    @Schema(name="OccurrenceParcel") public record Parcel(UUID id,UUID reservationId,int cents,String status,int commissionCents,int driverCents,String reason,
        String date,String time,String direction,String rideStatus,String reservationStatus,String availability,boolean monthly,String passengerName,String driverName,List<Related> relatedCases) {}
    @Schema(name="OccurrenceDetail") public record Detail(UUID id,String kind,String reason,String status,boolean severe,String outcome,String decision,
        Participants participants,List<Parcel> items,Integer nextOffset) {}
}
