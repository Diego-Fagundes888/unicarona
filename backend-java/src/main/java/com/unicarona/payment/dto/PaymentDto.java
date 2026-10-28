package com.unicarona.payment.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.unicarona.auth.dto.AuthDto;
import com.unicarona.shared.InputText;
import com.unicarona.shared.ApiException;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.unicarona.shared.OptionalNonNullIntegerDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public final class PaymentDto {
    private PaymentDto() {}
    @JsonPropertyOrder({"eventId","paymentId","reference","amountCents","status"})
    @Schema(name="PaymentWebhookEvent")
    public record Event(@NotBlank @Size(max=128) @Pattern(regexp="[a-zA-Z0-9_-]+") String eventId,
        @NotNull @Pattern(regexp=AuthDto.UUID_PATTERN) String paymentId,@NotBlank @Size(max=200) String reference,
        @NotNull @Min(1) @Max(100000000) @JsonDeserialize(using=OptionalNonNullIntegerDeserializer.class) Integer amountCents,@NotNull @Pattern(regexp="approved|failed") String status) {
        public Event {
            // The original HTTP schema checks length/pattern before Zod trims text.
            ApiException.require(reference==null || reference.length()<=200,"VALIDATION_ERROR","Confira os campos informados.",422);
            reference=InputText.trim(reference);
        }
    }
    @Schema(name="PaymentCheckoutInput") public record CheckoutInput(@NotNull @Pattern(regexp="pix|card") String method,@NotNull Boolean useCredit,
        @Pattern(regexp="[a-f0-9]{64}") @JsonDeserialize(using=com.unicarona.shared.OptionalNonNullStringDeserializer.class) String tripReview) {}
    @Schema(name="DevelopmentPaymentResult") public record ResultInput(@NotNull @Pattern(regexp="approved|failed") String status) {}
    @Schema(name="PaymentCheckout") public record Checkout(String url,String reference,String environment) {}
    @Schema(name="PaymentDetail") public record Detail(UUID id,String status,int amountCents,int creditCents,boolean creditRequested,int externalCents,String method,String expiresAt,boolean confirmed,Checkout checkout) {}
    public record Prepared(String paymentId,boolean confirmed) {}
    @Schema(name="PaymentTrip") public record Trip(UUID rideId,String date,String time,String direction,String origin,String destination,String meeting,String notes,int cents,int version,boolean detailsAvailable) {
        @Override public String toString(){return "PaymentTrip[redacted]";}
    }
    @Schema(name="PaymentSummary") public record Summary(String type,String driverName,int rideCount,long subtotalCents,long discountCents,long totalCents,long platformCents,long driverCents,java.util.List<Trip> trips,String tripReview) {}
    @Schema(name="PaymentReceived") public record Received(boolean received,boolean duplicate) {}
}
