package com.ledger;

import com.ledger.error.ApiException;
import com.ledger.posting.LedgerTransaction;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalTest extends IntegrationTest {

    @Test
    void reversalDuringHoldAlwaysSucceedsAndRestoresBalances() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        LedgerTransaction tx = transfer(a, b, 400, "USD");

        LedgerTransaction reversal = reversals.reverse(tx.id(), "sent to wrong account");

        assertThat(reversal.type()).isEqualTo(LedgerTransaction.Type.REVERSAL);
        assertThat(reversal.reversesId()).isEqualTo(tx.id());
        assertThat(balance(a)).isEqualTo(1_000);
        assertThat(available(a)).isEqualTo(1_000);   // returned money is spendable at once
        assertThat(balance(b)).isZero();
        assertThat(accounts.get(b).heldBalance()).isZero();   // the reversed credit is no longer "held"
        assertLedgerHealthy();
    }

    @Test
    void reversalOnlyClawsBackTheReversedCreditNotOtherHeldMoney() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        LedgerTransaction first = transfer(a, b, 300, "USD");
        transfer(a, b, 200, "USD");

        reversals.reverse(first.id(), null);

        assertThat(balance(b)).isEqualTo(200);
        assertThat(accounts.get(b).heldBalance()).isEqualTo(200);
        assertThat(available(b)).isZero();
    }

    @Test
    void cannotReverseTwice() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        LedgerTransaction tx = transfer(a, b, 100, "USD");
        reversals.reverse(tx.id(), null);

        assertApiError("ALREADY_REVERSED", () -> reversals.reverse(tx.id(), null));
        assertThat(balance(a)).isEqualTo(1_000);
    }

    @Test
    void cannotReverseAReversal() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        LedgerTransaction reversal = reversals.reverse(transfer(a, b, 100, "USD").id(), null);
        assertApiError("CANNOT_REVERSE_REVERSAL", () -> reversals.reverse(reversal.id(), null));
    }

    @Test
    void afterHoldReversalFailsIfRecipientAlreadySpentTheMoney() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        UUID c = newAccount("USD");
        LedgerTransaction tx = transfer(a, b, 500, "USD");

        clock.advance(Duration.ofMinutes(11));
        transfer(b, c, 450, "USD");   // B spends most of it

        assertApiError("INSUFFICIENT_FUNDS", () -> reversals.reverse(tx.id(), null));
        assertThat(balance(a)).isEqualTo(500);
        assertThat(balance(b)).isEqualTo(50);
    }

    @Test
    void afterHoldReversalSucceedsIfMoneyIsStillThere() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        LedgerTransaction tx = transfer(a, b, 500, "USD");
        clock.advance(Duration.ofHours(2));

        reversals.reverse(tx.id(), null);
        assertThat(balance(a)).isEqualTo(1_000);
        assertThat(balance(b)).isZero();
    }

    @Test
    void depositsCanBeReversed() {
        UUID a = newAccount("USD");
        LedgerTransaction dep = deposit(a, 700, "USD");
        reversals.reverse(dep.id(), "chargeback");
        assertThat(balance(a)).isZero();
        assertLedgerHealthy();
    }

    @Test
    void withdrawalsCanBeReversedAndMoneyIsAvailableAtOnce() {
        UUID a = fundedAccount("USD", 1_000);
        LedgerTransaction w = transfers.withdraw(key(), a, 400, "USD", null).transaction();
        reversals.reverse(w.id(), "ATM failed to dispense");
        assertThat(balance(a)).isEqualTo(1_000);
        assertThat(available(a)).isEqualTo(1_000);
        assertLedgerHealthy();
    }

    @Test
    void fxReversalAfterRecipientSpentIsRejectedAndChangesNothing() {
        UUID usd = fundedAccount("USD", 10_000);
        UUID sgd = newAccount("SGD");
        UUID other = newAccount("SGD");
        LedgerTransaction tx = transfer(usd, sgd, 10_000, "USD");   // -> 13450 SGD
        clock.advance(Duration.ofMinutes(10));
        transfer(sgd, other, 13_000, "SGD");
        long fxUsdBefore = fxBalance("USD");

        assertApiError("INSUFFICIENT_FUNDS", () -> reversals.reverse(tx.id(), null));
        assertThat(balance(usd)).isZero();
        assertThat(fxBalance("USD")).isEqualTo(fxUsdBefore);
    }

    @Test
    void reversalHeaderCarriesNoRateOfItsOwn() {
        UUID usd = fundedAccount("USD", 10_000);
        UUID sgd = newAccount("SGD");
        LedgerTransaction reversal = reversals.reverse(transfer(usd, sgd, 1_000, "USD").id(), null);
        assertThat(reversal.fxRateScaled()).isNull();
        assertThat(reversal.sourceCurrency()).isEqualTo("SGD");
        assertThat(reversal.destinationAmount()).isEqualTo(1_000);
    }

    private long fxBalance(String currency) {
        return jdbc.sql("SELECT balance FROM accounts WHERE system_role = 'FX' AND currency = :c")
                .param("c", currency).query(Long.class).single();
    }

    @Test
    void unknownTransactionIsNotFound() {
        assertApiError("NOT_FOUND", () -> reversals.reverse(UUID.randomUUID(), null));
    }

    @Test
    void concurrentReversalsOfTheSameTransactionApplyOnce() throws Exception {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        LedgerTransaction tx = transfer(a, b, 300, "USD");
        int threads = 10;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        reversals.reverse(tx.id(), null);
                        succeeded.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo("ALREADY_REVERSED");
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(balance(a)).isEqualTo(1_000);
        assertLedgerHealthy();
    }
}
