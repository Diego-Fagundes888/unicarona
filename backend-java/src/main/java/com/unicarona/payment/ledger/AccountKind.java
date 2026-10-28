package com.unicarona.payment.ledger;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AccountKind {
    PROVIDER_CLEARING, FUTURE, BLOCKED, DRIVER_AVAILABLE, PASSENGER_CREDIT, COMMISSION, RETAINED, PAYOUT_PENDING, REFUND_PENDING, PAID_OUT, REFUNDED, PASSENGER_RECEIVABLE;
    @JsonValue public String value() { return name().toLowerCase(java.util.Locale.ROOT); }
}
