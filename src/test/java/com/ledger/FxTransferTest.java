package com.ledger;

import com.ledger.fx.FxRateService;
import com.ledger.posting.Entry;
import com.ledger.posting.LedgerTransaction;
import com.ledger.posting.TransactionRepository;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FxTransferTest extends IntegrationTest {

    @Autowired TransactionRepository transactionRepository;
    @Autowired FxRateService fx;

    @Test
    void crossCurrencyTransferConvertsAndKeepsEachCurrencyBalanced() {
        UUID usd = fundedAccount("USD", 50_000);
        UUID sgd = newAccount("SGD");

        LedgerTransaction tx = transfer(usd, sgd, 10_000, "USD");   // 100.00 USD @ 1.345

        assertThat(tx.destinationAmount()).isEqualTo(13_450);        // 134.50 SGD
        assertThat(tx.fxRateScaled()).isEqualTo(134_500_000L);
        assertThat(balance(usd)).isEqualTo(40_000);
        assertThat(balance(sgd)).isEqualTo(13_450);

        List<Entry> entries = transactionRepository.entriesOf(tx.id());
        assertThat(entries).hasSize(4);
        Map<String, Long> netByCurrency = entries.stream()
                .collect(Collectors.groupingBy(Entry::currency, Collectors.summingLong(Entry::amount)));
        assertThat(netByCurrency).containsEntry("USD", 0L).containsEntry("SGD", 0L);
        assertLedgerHealthy();
    }

    @Test
    void convertsIntoZeroDecimalCurrency() {
        UUID usd = fundedAccount("USD", 50_000);
        UUID jpy = newAccount("JPY");
        LedgerTransaction tx = transfer(usd, jpy, 10_000, "USD");    // 100.00 USD @ 150.25
        assertThat(balance(jpy)).isEqualTo(15_025);
        assertThat(tx.destinationCurrency()).isEqualTo("JPY");
    }

    @Test
    void reversingAnFxTransferReturnsTheExactOriginalAmountEvenIfTheRateChanged() {
        UUID usd = fundedAccount("USD", 50_000);
        UUID eur = newAccount("EUR");
        LedgerTransaction tx = transfer(usd, eur, 10_000, "USD");
        assertThat(balance(eur)).isEqualTo(9_200);

        fx.upsert("USD", "EUR", "0.5");   // rate moves; the reversal must still use the original legs
        try {
            reversals.reverse(tx.id(), null);
        } finally {
            fx.upsert("USD", "EUR", "0.92");
        }

        assertThat(balance(usd)).isEqualTo(50_000);
        assertThat(balance(eur)).isZero();
        assertLedgerHealthy();
    }

    @Test
    void missingRateIsRejected() {
        UUID eur = fundedAccount("EUR", 1_000);
        UUID jpy = newAccount("JPY");
        assertApiError("FX_RATE_UNAVAILABLE", () -> transfer(eur, jpy, 100, "EUR"));
        assertThat(balance(eur)).isEqualTo(1_000);
    }

    @Test
    void amountThatConvertsToZeroIsRejected() {
        fx.upsert("EUR", "GBP", "0.001");
        UUID eur = fundedAccount("EUR", 1_000);
        UUID gbp = newAccount("GBP");
        assertApiError("AMOUNT_TOO_SMALL", () -> transfer(eur, gbp, 1, "EUR"));   // 0.01 EUR -> 0.00001 GBP
        assertThat(balance(eur)).isEqualTo(1_000);
    }

    @Test
    void rateValidation() {
        assertApiError("INVALID_RATE", () -> fx.upsert("USD", "USD", "1"));
        assertApiError("INVALID_RATE", () -> fx.upsert("USD", "SGD", "-1"));
        assertApiError("INVALID_RATE", () -> fx.upsert("USD", "SGD", "1.123456789"));
        assertApiError("UNSUPPORTED_CURRENCY", () -> fx.upsert("USD", "XYZ", "1"));
    }
}
