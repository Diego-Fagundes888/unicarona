package com.unicarona.admin.dto;

import com.unicarona.shared.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.*;

public final class AdminAccountDto {
    private AdminAccountDto() {}
    @Schema(name="AdminAccountReviewInput") public record Review(@NotNull @Pattern(regexp="active|suspended") String status,
        @NotNull @Pattern(regexp="active|suspended|closing|deactivated") String expectedStatus,@NotBlank @Size(min=3,max=1000) String reason) {
        public Review { reason=AdminCatalogDto.text(reason,1000); }
    }
    @Schema(name="AdminAccount") public record Account(UUID id,String name,String email,String status,String university,String course,String driverStatus) {}
    @Schema(name="ReviewedAccount") public record Reviewed(String id,String status,int affectedParcels,UUID financialCaseId) {}
    public record Query(String search,String status,Pagination page) {}
    public static Query query(Map<String,String> query) {
        var page=Pagination.parse(query,Set.of("q","status"));String raw=query.get("q"),search=InputText.trim(raw),status=query.get("status");ApiException.require((raw==null || raw.length()<=80) && (search==null || !search.isEmpty() && search.length()<=80) && (status==null || Set.of("active","suspended","closing","deactivated","banned").contains(status)),"VALIDATION_ERROR","Confira os campos informados.",422);return new Query(search==null?"":search,status,page);
    }
}
