package com.unicarona.subscription.dto;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.ApiException;
import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public final class ContractDto {
    private ContractDto() {}
    @Schema(name="MonthlyContract")
    public record Item(UUID id,UUID routineId,UUID driverId,UUID passengerId,String startDate,String endDate,
        List<Integer> weekdays,String directions,int totalCents,double discount,String status,boolean renew,
        String graceUntil,long absenceCount,long heldLegs,String otherName,String region) {}
    @Schema(name="MonthlyContractRights") public record Rights(boolean openRights) {}
    @Schema(name="ContractEmptyInput") public record Empty() {}
    @Schema(name="ContractRemovalInput") public record Remove(@NotBlank @Size(min=3,max=2000) String reason) {
        public Remove {
            ApiException.require(reason==null || reason.length()<=2000,"VALIDATION_ERROR","Confira os campos informados.",422);
            reason=InputText.trim(reason);
        }
    }
    @Schema(name="WaitlistInput") public record Wait(@NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String routineId) {}
    public record Status(String id,String status) {}
    public record Interest(UUID id,UUID routineId,boolean priority,String region,String driverName) {}
    public record Interests(List<Interest> items) {}
}
