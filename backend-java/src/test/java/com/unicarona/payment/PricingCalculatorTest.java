package com.unicarona.payment;

import com.unicarona.payment.service.PricingCalculator;
import com.unicarona.shared.ApiException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PricingCalculatorTest {
    private final PricingCalculator calculator = new PricingCalculator();
    private final PricingCalculator.Parameters pricing = new PricingCalculator.Parameters(600, BigDecimal.TEN, 20, 2, 1000, 1000);
    @Test void documentPriceAndFrozenFeeUseExactCents() {
        assertEquals(593, calculator.quote(20000, 0, 3, pricing));
        assertEquals(59, calculator.commission(593, 1000));
    }
    @Test void monthlyAllocationPreservesTotalAndDeterministicRemainder() {
        var allocation = calculator.allocateMonthly(Collections.nCopies(40, 593L), 1000);
        assertEquals(21348, allocation.totalCents());
        assertEquals(21348, allocation.allocations().stream().mapToLong(Long::longValue).sum());
        assertEquals(28, allocation.allocations().stream().filter(value -> value == 534).count());
        assertEquals(21600, calculator.allocateMonthly(Collections.nCopies(40, 600L), 1000).totalCents());
        assertThrows(UnsupportedOperationException.class, () -> allocation.allocations().add(1L));
    }
    @Test void partialMonthAndInvalidInputs() {
        assertEquals(9720, calculator.allocateMonthly(Collections.nCopies(18, 600L), 1000).totalCents());
        assertThrows(ApiException.class, () -> calculator.allocateMonthly(List.of(), 1000));
        assertThrows(ApiException.class, () -> calculator.quote(20000, 0, 0, pricing));
        assertThrows(ApiException.class, () -> calculator.allocateMonthly(List.of(600L), 10000));
    }
    @Test void monetaryInputRejectsFractionsOfACent() {
        assertEquals(593, calculator.inputCents("5,93"));
        assertEquals(600, calculator.inputCents("6.00"));
        assertThrows(ApiException.class, () -> calculator.inputCents("5.934"));
    }
}
