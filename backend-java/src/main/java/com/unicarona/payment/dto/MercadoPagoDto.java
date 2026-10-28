package com.unicarona.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unicarona.payment.provider.MercadoPagoOrdersClient;
import com.unicarona.shared.ApiException;
import jakarta.validation.constraints.*;

public final class MercadoPagoDto {
  private MercadoPagoDto() {}

  public record CardInput(
      @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,256}") String token,
      @NotNull @Pattern(regexp = "[A-Za-z0-9_]{1,32}") String method,
      @NotNull @Pattern(regexp = "credit_card|debit_card") String type,
      @NotNull @Min(1) @Max(1) Integer installments,
      @NotNull @Pattern(regexp = "CPF|CNPJ") String identificationType,
      @NotNull @Pattern(regexp = "[0-9]{11}|[0-9]{14}") String identificationNumber,
      @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "[\\x21-\\x7E]{8,1024}")
          String deviceSessionId) {
    public CardInput {
      if (identificationType != null && identificationNumber != null)
        ApiException.require(
            identificationNumber.length() == ("CPF".equals(identificationType) ? 11 : 14),
            "CARD_DATA_INVALID",
            "Confira o documento do titular do cartão.",
            422);
    }

    public MercadoPagoOrdersClient.Card card() {
      return new MercadoPagoOrdersClient.Card(
          token,
          method,
          type,
          installments,
          identificationType,
          identificationNumber,
          deviceSessionId);
    }

    @Override
    public String toString() {
      return "CardInput[REDACTED]";
    }
  }

  public record GatewayDetail(
      String state,
      String statusDetail,
      String qrCode,
      String qrBase64,
      String ticketUrl,
      String environment,
      boolean cardSubmitted,
      String lastErrorCode,
      String challengeUrl) {}

  public record Configuration(
      String provider,
      String environment,
      String publicKey,
      boolean cardEnabled,
      boolean payoutEnabled,
      boolean refundEnabled) {}

  public record RefundDetail(
      java.util.UUID transferId,
      String status,
      int requestedCents,
      long returnedCents,
      long restoredCents,
      long processingCents) {}

  public record RefundReview(boolean creditUnderReview) {}
}
