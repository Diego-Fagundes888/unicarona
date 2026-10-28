package com.unicarona.payment.provider;

/** Orders status is distinct from the immutable receipt and reservation ledger. */
public enum MercadoPagoStatus {
    CREATED, PENDING, PROCESSING, APPROVED, REJECTED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED, EXPIRED, UNDER_REVIEW;
    public static MercadoPagoStatus map(String status,String detail) {
        if("partially_refunded".equals(detail)) return PARTIALLY_REFUNDED;
        if("refunded".equals(status) || "refunded".equals(detail)) return REFUNDED;
        if("charged_back".equals(status) || "charged_back".equals(detail)) return UNDER_REVIEW;
        return switch(status) {
            case "created" -> CREATED;
            case "processing" -> PROCESSING;
            case "action_required" -> PENDING;
            case "processed" -> "accredited".equals(detail)?APPROVED:PROCESSING;
            case "failed" -> REJECTED;
            case "canceled", "cancelled" -> "expired".equals(detail)?EXPIRED:CANCELLED;
            case "expired" -> EXPIRED;
            default -> UNDER_REVIEW;
        };
    }
}
