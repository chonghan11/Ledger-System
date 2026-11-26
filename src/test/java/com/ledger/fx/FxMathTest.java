package com.ledger.fx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for integer FX conversion: no Spring, no DB. */
class FxMathTest {

    private static final long USD_SGD = 134_500_000L;    // 1.345
    private static final long USD_JPY = 15_025_000_000L; // 150.25

    @Test
    void convertsBetweenTwoDecimalCurrencies() {
        // 100.00 USD * 1.345 = 134.50 SGD
        assertThat(FxMath.convert(10_000, 2, 2, USD_SGD)).isEqualTo(13_450);
    }

    @Test
    void handlesDifferentMinorUnits() {
        // 100.00 USD (10000 cents) * 150.25 = 15025 JPY (JPY has no minor unit)
        assertThat(FxMath.convert(10_000, 2, 0, USD_JPY)).isEqualTo(15_025);
        // 15025 JPY * 0.00665557 = 99.9999... USD -> 10000 cents after rounding
        assertThat(FxMath.convert(15_025, 0, 2, 665_557)).isEqualTo(10_000);
    }

    @Test
    void roundsHalfToEven() {
        long onePointFive = 150_000_000L;  // 1.5
        long twoPointFive = 250_000_000L;  // 2.5
        assertThat(FxMath.convert(1, 2, 2, onePointFive)).isEqualTo(2);  // 1.5 -> 2 (even)
        assertThat(FxMath.convert(1, 2, 2, twoPointFive)).isEqualTo(2);  // 2.5 -> 2 (even)
        assertThat(FxMath.convert(3, 2, 2, onePointFive)).isEqualTo(4);  // 4.5 -> 4 (even)
        assertThat(FxMath.convert(1, 2, 2, 134_500_000L)).isEqualTo(1);  // 1.345 -> 1
        assertThat(FxMath.convert(1, 2, 2, 175_000_000L)).isEqualTo(2);  // 1.75 -> 2
    }

    @Test
    void canConvertToZeroForTinyAmounts() {
        assertThat(FxMath.convert(1, 2, 2, 100_000L)).isZero();         // 0.01 * 0.001
    }

    @Test
    void doesNotOverflowOnLargeAmounts() {
        // Intermediate product exceeds Long.MAX_VALUE but the result fits.
        long big = 4_000_000_000_000_000_000L;   // 4 x 10^18, Long.MAX_VALUE is ~9.22 x 10^18
        assertThat(FxMath.convert(big, 2, 2, 100_000_000L)).isEqualTo(big);              // x1.0 fits
        assertThatThrownBy(() -> FxMath.convert(big, 2, 2, 300_000_000L))                // x3.0 does not
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void parsesRatesExactly() {
        assertThat(FxMath.parse("1.345")).isEqualTo(134_500_000L);
        assertThat(FxMath.parse("150.25")).isEqualTo(15_025_000_000L);
        assertThat(FxMath.parse("0.00000001")).isEqualTo(1L);
        assertThat(FxMath.format(134_500_000L)).isEqualTo("1.34500000");
    }

    @Test
    void rejectsInvalidRates() {
        assertThatThrownBy(() -> FxMath.parse("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.parse("-1.2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.parse("1.123456789")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.parse("abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
