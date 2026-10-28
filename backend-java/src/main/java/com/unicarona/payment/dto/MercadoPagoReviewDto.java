package com.unicarona.payment.dto;

import com.unicarona.shared.InputText;
import jakarta.validation.constraints.*;
import java.util.*;

public final class MercadoPagoReviewDto {
    private MercadoPagoReviewDto() {}
    public record Item(UUID paymentId,UUID passengerId,String passengerName,String orderReference,String paymentStatus,String state,
        String providerStatus,String statusDetail,int originalCents,int externalCents,int refundedCents,
        boolean untrackedRefunds,boolean historyIncomplete,boolean chargebackReview,String checkedAt,String errorCode) {}
    public record ObservedRefund(String reference,String transactionReference,int cents,String status,boolean linked,boolean reconciled,String observedAt) {}
    public record Resolution(boolean available,String kind,String evidenceKey,long reversalCents,long heldCreditCents,long registeredDebtCents,long dueCents,List<String> blockers) {}
    public record Detail(Item payment,FinanceDto.Wallet wallet,List<ObservedRefund> refunds,boolean refundsTruncated,int pendingRefundCommands,int uncertainRefundCommands,Resolution resolution) {}
    public record Recheck(@NotBlank @Size(min=3,max=1000) String reason) {
        public Recheck {reason=InputText.trim(reason);}
    }
    public record Checked(boolean checked) {}
    public record Resolve(@NotBlank @Size(min=3,max=1000) String reason,@NotNull @Pattern(regexp="[a-f0-9]{64}") String evidenceKey) {public Resolve {reason=InputText.trim(reason);}}
    public record Resolved(UUID resolutionId,boolean resolved,long creditAdjustedCents,long registeredDebtCents) {}
}
