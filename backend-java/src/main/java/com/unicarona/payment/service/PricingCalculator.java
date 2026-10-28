package com.unicarona.payment.service;

import com.unicarona.shared.ApiException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PricingCalculator {
    public record Parameters(long fuelCents, BigDecimal efficiency, long wearCents, int referencePassengers, int commissionBps, int discountBps) {}
    public record MonthlyAllocation(long originalCents, long totalCents, List<Long> allocations) {
        public MonthlyAllocation { allocations = List.copyOf(allocations); }
    }
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10000);
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);
    public long quote(int distanceM, long tollCents, int capacity, Parameters pricing) {
        ApiException.require(distanceM >= 100 && distanceM <= 2_000_000, "INVALID_DISTANCE", "Informe uma distância válida.", 422);
        ApiException.require(tollCents >= 0 && tollCents <= 200000, "INVALID_TOLL", "Informe um pedágio válido.", 422);
        ApiException.require(capacity >= 1 && capacity <= 8, "INVALID_CAPACITY", "Informe vagas válidas.", 422);
        ApiException.require(pricing.efficiency().signum() > 0 && pricing.commissionBps() >= 0 && pricing.commissionBps() < 10000,
            "INVALID_PRICE", "Parâmetros de preço inválidos.", 422);
        BigInteger efficiency = pricing.efficiency().multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
        BigInteger numerator = BigInteger.valueOf(distanceM).multiply(BigInteger.valueOf(pricing.fuelCents()).multiply(THOUSAND)
            .add(BigInteger.valueOf(pricing.wearCents()).multiply(efficiency)))
            .add(BigInteger.valueOf(tollCents).multiply(THOUSAND).multiply(efficiency));
        BigInteger denominator = THOUSAND.multiply(efficiency).multiply(BigInteger.valueOf(1 + Math.min(pricing.referencePassengers(), capacity)))
            .multiply(BigInteger.valueOf(10000 - pricing.commissionBps()));
        return roundRational(numerator.multiply(TEN_THOUSAND), denominator);
    }
    public MonthlyAllocation allocateMonthly(List<Long> prices, int discountBps) {
        ApiException.require(!prices.isEmpty() && prices.stream().allMatch(price -> price != null && price > 0), "EMPTY_SCHEDULE", "Não há trechos elegíveis nessa agenda.", 422);
        ApiException.require(discountBps >= 0 && discountBps < 10000, "INVALID_DISCOUNT", "Desconto inválido.", 422);
        BigInteger factor = BigInteger.valueOf(10000 - discountBps);
        BigInteger original = prices.stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        long total = roundRational(original.multiply(factor), TEN_THOUSAND);
        List<Long> allocations = new ArrayList<>(prices.stream().map(price -> BigInteger.valueOf(price).multiply(factor).divide(TEN_THOUSAND).longValueExact()).toList());
        long remainder = total - allocations.stream().mapToLong(Long::longValue).sum();
        for (int i = 0; remainder > 0; i++, remainder--) allocations.set(i % allocations.size(), allocations.get(i % allocations.size()) + 1);
        return new MonthlyAllocation(original.longValueExact(), total, allocations);
    }
    public long commission(long cents, int bps) { return roundRational(BigInteger.valueOf(cents).multiply(BigInteger.valueOf(bps)), TEN_THOUSAND); }
    public long inputCents(String value) {
        String raw = value.replace(',', '.');
        ApiException.require(raw.matches("\\d{1,9}(\\.\\d{1,2})?"), "INVALID_AMOUNT", "Informe um valor com até duas casas decimais.", 422);
        return new BigDecimal(raw).movePointRight(2).longValueExact();
    }
    private long roundRational(BigInteger numerator, BigInteger denominator) {
        ApiException.require(denominator.signum() > 0, "INVALID_PRICE", "Parâmetros de preço inválidos.", 422);
        return numerator.add(denominator.divide(BigInteger.TWO)).divide(denominator).longValueExact();
    }
}
