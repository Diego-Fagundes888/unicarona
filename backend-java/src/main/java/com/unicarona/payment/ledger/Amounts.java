package com.unicarona.payment.ledger;

import com.unicarona.shared.ApiException;
import java.math.BigInteger;

public final class Amounts {
    public static final long MAXIMUM_SAFE=9007199254740991L;
    private static final BigInteger MAXIMUM=BigInteger.valueOf(MAXIMUM_SAFE);
    private Amounts() {}
    public static long exact(BigInteger value) {
        ApiException.require(value.abs().compareTo(MAXIMUM)<=0,"AMOUNT_OVERFLOW","Saldo fora do limite de cálculo.",500);return value.longValueExact();
    }
    public static long parse(String value) { return exact(new BigInteger(value)); }
    public static long add(long a,long b) { return exact(BigInteger.valueOf(a).add(BigInteger.valueOf(b))); }
}
