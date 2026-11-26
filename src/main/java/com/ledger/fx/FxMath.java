package com.ledger.fx;

import java.math.BigInteger;

/**
 * Integer-only currency conversion.
 *
 * <p>Rates are stored as {@code rate x 10^8} in MAJOR units (1 USD = 1.345 SGD -> 134_500_000).
 * Converting {@code a} minor units of a currency with {@code s} decimals into one with {@code d} decimals:
 * <pre>
 *   result = a * rateScaled * 10^d  /  (10^8 * 10^s)
 * </pre>
 * The intermediate product is computed with BigInteger (no overflow), and the single division is
 * rounded HALF_EVEN (banker's rounding) so rounding errors do not drift in one direction over many
 * conversions. This is the only rounding step in the whole ledger.
 */
public final class FxMath {

    public static final int RATE_DECIMALS = 8;
    public static final long RATE_SCALE = 100_000_000L;

    private FxMath() {}

    public static long convert(long amountMinor, int sourceMinorUnits, int targetMinorUnits, long rateScaled) {
        if (amountMinor < 0) throw new IllegalArgumentException("amount must be non-negative");
        if (rateScaled <= 0) throw new IllegalArgumentException("rate must be positive");

        BigInteger numerator = BigInteger.valueOf(amountMinor)
                .multiply(BigInteger.valueOf(rateScaled))
                .multiply(BigInteger.TEN.pow(targetMinorUnits));
        BigInteger denominator = BigInteger.valueOf(RATE_SCALE)
                .multiply(BigInteger.TEN.pow(sourceMinorUnits));

        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        BigInteger quotient = qr[0];
        int halfComparison = qr[1].shiftLeft(1).compareTo(denominator);   // compare remainder with denominator / 2
        if (halfComparison > 0 || (halfComparison == 0 && quotient.testBit(0))) {
            quotient = quotient.add(BigInteger.ONE);                       // round up; ties go to the even neighbour
        }
        return quotient.longValueExact();                                  // throws rather than overflow
    }

    /** Formats a scaled rate for display, e.g. 134500000 -> "1.34500000". */
    public static String format(long rateScaled) {
        return new java.math.BigDecimal(BigInteger.valueOf(rateScaled), RATE_DECIMALS).toPlainString();
    }

    /** Parses a decimal string such as "1.345" exactly (no floating point) into a scaled rate. */
    public static long parse(String rate) {
        java.math.BigDecimal value;
        try {
            value = new java.math.BigDecimal(rate.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("rate is not a decimal number");
        }
        if (value.signum() <= 0) throw new IllegalArgumentException("rate must be positive");
        value = value.stripTrailingZeros();   // "1.500000000" is exactly 1.5: accept it
        if (value.scale() > RATE_DECIMALS) throw new IllegalArgumentException("rate supports at most 8 decimal places");
        return value.movePointRight(RATE_DECIMALS).longValueExact();
    }
}
