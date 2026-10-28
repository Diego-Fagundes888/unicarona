package com.unicarona.payment.dto;

public final class FinanceDto {
    private FinanceDto() {}
    public record Wallet(long future,long blocked,long available,long credit,long processing,long withdrawn,long returned) {}
    public record Reconciliation(long received,long allocated,long difference,long future,long blocked,long driver,long credit,long commission,long retained,long processing,long withdrawn,long returned) {}
}
