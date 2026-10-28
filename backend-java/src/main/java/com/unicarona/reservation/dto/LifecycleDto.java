package com.unicarona.reservation.dto;

import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class LifecycleDto {
    private LifecycleDto() {}
    @Schema(name="LifecycleEmptyInput") public record Empty() {}
    @Schema(name="LifecycleReasonInput") public record Reason(@NotBlank @Size(min=3,max=2000) String reason) {
        public Reason { checkLength(reason);reason=InputText.trim(reason); }
    }
    @Schema(name="BoardingInput") public record Board(@NotNull @Pattern(regexp="[0-9]{4}") String pin) { @Override public String toString() { return "Board[redacted]"; } }
    @Schema(name="FinishRideInput") public record Finish(@NotNull Boolean availability) {}
    @Schema(name="TimeResponseInput") public record Response(@NotNull Boolean accept) {}
    @Schema(name="TimeProposalInput") public record Proposal(@NotNull @Pattern(regexp="([01][0-9]|2[0-3]):[0-5][0-9]") String time,@NotBlank @Size(min=3,max=2000) String reason) {
        public Proposal { checkLength(reason);reason=InputText.trim(reason); }
    }
    private static void checkLength(String value) { ApiException.require(value==null || value.length()<=2000,"VALIDATION_ERROR","Confira os campos informados.",422); }
    @Schema(name="LifecycleStatus") public record Status(String id,String status) {}
    @Schema(name="PassengerCancellation") public record Cancelled(String id,String status,UUID occurrenceId) {}
    @Schema(name="MonthlyAbsence") public record Absence(String id,String absence) {}
    @Schema(name="TimeResponse") public record Answer(String id,String response) {}
    @Schema(name="PreservedTime") public record Time(String id,String time) {}
}
