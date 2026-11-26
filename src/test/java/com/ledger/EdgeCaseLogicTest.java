package com.ledger;

import com.ledger.audit.ReconciliationService;
import com.ledger.error.ApiException;
import com.ledger.posting.LedgerTransaction;
import com.ledger.support.IntegrationTest;
import com.ledger.transaction.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases that walk through the ledger's rules with concrete numbers. Each test name states the rule it proves;
 * docs/logic-walkthrough.md explains every one of them.
 */
class EdgeCaseLogicTest extends IntegrationTest {

    @Autowired ReconciliationService reconciliation;

    // ------------------------------------------------------------------ funds check & holds

    @Test
    void canSpendExactlyTheAvailableAmountButNotOneCentMoreWhileOtherMoneyIsHeld() {
        UUID a = fundedAccount("USD", 1_000);
        UUID x = fundedAccount("USD", 300);
        UUID b = newAccount("USD");
        transfer(x, a, 300, "USD");                      // A: balance 1300, held 300, available 1000

        assertThat(balance(a)).isEqualTo(1_300);
        assertThat(available(a)).isEqualTo(1_000);
        assertApiError("INSUFFICIENT_FUNDS", () -> transfer(a, b, 1_001, "USD"));
        transfer(a, b, 1_000, "USD");                    // exactly the available amount
        assertThat(balance(a)).isEqualTo(300);           // only the held money is left
        assertThat(available(a)).isZero();
    }

    @Test
    void heldMoneyCannotBeWithdrawnEither() {
        UUID a = fundedAccount("USD", 500);
        UUID b = newAccount("USD");
        transfer(a, b, 500, "USD");
        assertApiError("INSUFFICIENT_FUNDS", () -> transfers.withdraw(key(), b, 1, "USD", null));
        clock.advance(Duration.ofMinutes(10));
        transfers.withdraw(key(), b, 500, "USD", null);
        assertThat(balance(b)).isZero();
    }

    // ------------------------------------------------------------------ reversals

    @Test
    void reversalChainUnwindsInTheRightOrder() {
        UUID a = fundedAccount("USD", 500);
        UUID b = newAccount("USD");
        UUID c = newAccount("USD");
        LedgerTransaction ab = transfer(a, b, 500, "USD");
        clock.advance(Duration.ofMinutes(11));           // B's hold expires
        LedgerTransaction bc = transfer(b, c, 500, "USD"); // B passes it on; C's copy is held

        // A->B cannot be undone: B no longer has the money.
        assertApiError("INSUFFICIENT_FUNDS", () -> reversals.reverse(ab.id(), null));
        // B->C can be undone: C's credit is still held, so the reversal always succeeds...
        reversals.reverse(bc.id(), null);
        assertThat(available(b)).isEqualTo(500);         // ...and B gets the money back immediately
        // Now A->B can be undone too.
        reversals.reverse(ab.id(), null);
        assertThat(balance(a)).isEqualTo(500);
        assertThat(balance(b)).isZero();
        assertThat(balance(c)).isZero();
        assertLedgerHealthy();
    }

    @Test
    void reversingADepositAfterTheMoneyWasSpentIsRejected() {
        UUID a = newAccount("USD");
        UUID b = newAccount("USD");
        LedgerTransaction dep = deposit(a, 700, "USD");
        transfer(a, b, 600, "USD");
        assertApiError("INSUFFICIENT_FUNDS", () -> reversals.reverse(dep.id(), "chargeback"));
        assertThat(balance(a)).isEqualTo(100);           // nothing changed
    }

    @Test
    void racingReversalAndSpendNeverBothSucceed() throws Exception {
        // After the hold, B can spend the money OR A's transfer can be reversed, never both.
        // Repeated to give the race many chances to go wrong.
        for (int round = 0; round < 15; round++) {
            UUID a = fundedAccount("USD", 500);
            UUID b = newAccount("USD");
            UUID c = newAccount("USD");
            LedgerTransaction ab = transfer(a, b, 500, "USD");
            clock.advance(Duration.ofMinutes(11));

            AtomicInteger succeeded = new AtomicInteger();
            race(
                    () -> { try { reversals.reverse(ab.id(), null); succeeded.incrementAndGet(); } catch (ApiException e) { assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"); } },
                    () -> { try { transfer(b, c, 500, "USD"); succeeded.incrementAndGet(); } catch (ApiException e) { assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"); } });

            assertThat(succeeded.get()).as("round %d", round).isEqualTo(1);
            assertThat(balance(b)).isZero();
            assertThat(balance(a) + balance(c)).isEqualTo(500);   // the 500 ended up in exactly one place
        }
        assertLedgerHealthy();
    }

    // ------------------------------------------------------------------ FX rounding

    @Test
    void fxRoundingTiesGoToTheEvenCent() {
        UUID usd = fundedAccount("USD", 100_000);
        UUID sgd = newAccount("SGD");
        // 25.00 * 1.345 = 33.625  -> tie between 33.62 and 33.63 -> 33.62 (2 is even)
        assertThat(transfer(usd, sgd, 2_500, "USD").destinationAmount()).isEqualTo(3_362);
        // 27.00 * 1.345 = 36.315  -> tie between 36.31 and 36.32 -> 36.32 (2 is even)
        assertThat(transfer(usd, sgd, 2_700, "USD").destinationAmount()).isEqualTo(3_632);
    }

    @Test
    void oneYenBecomesOneCentAndARoundTripBalancesTheFxAccounts() {
        UUID jpy = fundedAccount("JPY", 1_000);
        UUID usd = fundedAccount("USD", 10_000);
        UUID sgd = newAccount("SGD");
        // 1 JPY * 0.00665557 = 0.665 cents -> rounds to 1 cent
        assertThat(transfer(jpy, usd, 1, "JPY").destinationAmount()).isEqualTo(1);

        long fxUsdBefore = fxBalance("USD");
        long fxSgdBefore = fxBalance("SGD");
        transfer(usd, sgd, 10_000, "USD");               // 100.00 USD -> 134.50 SGD
        clock.advance(Duration.ofMinutes(10));
        LedgerTransaction back = transfer(sgd, usd, 13_450, "SGD");   // 134.50 SGD * 0.7435 = 100.000750 -> 100.00 USD
        assertThat(back.destinationAmount()).isEqualTo(10_000);
        // With these two seeded rates the round trip happens to be exact, so both FX accounts end where they began.
        assertThat(fxBalance("USD")).isEqualTo(fxUsdBefore);
        assertThat(fxBalance("SGD")).isEqualTo(fxSgdBefore);
        assertLedgerHealthy();
    }

    // ------------------------------------------------------------------ idempotency semantics

    @Test
    void theKeyDefinesTheRequestNotTheBody() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        UUID other = fundedAccount("USD", 1_000);
        String key = key();
        transfers.transfer(key, a, b, 100, "USD", null);

        // Same key from a different source account: a different request, refused.
        assertApiError("IDEMPOTENCY_KEY_REUSED", () -> transfers.transfer(key, other, b, 100, "USD", null));
        // Same body with a NEW key: a genuinely new payment (e.g. paying rent twice on purpose), executed.
        TransferService.Result second = transfers.transfer(key(), a, b, 100, "USD", null);
        assertThat(second.replayed()).isFalse();
        assertThat(balance(b)).isEqualTo(200);
    }

    // ------------------------------------------------------------------ limits

    @Test
    void aPostingThatWouldOverflowABalanceIsRejectedCleanly() {
        UUID a = fundedAccount("USD", 1);
        // Simulate an account sitting just below the 64-bit limit (only reachable after ~9,000 max-size deposits).
        jdbc.sql("UPDATE accounts SET balance = :b WHERE id = :id").param("b", Long.MAX_VALUE - 10).param("id", a).update();
        try {
            assertApiError("AMOUNT_OUT_OF_RANGE", () -> deposit(a, 100, "USD"));
            assertThat(balance(a)).isEqualTo(Long.MAX_VALUE - 10);   // rolled back, never wrapped to negative
        } finally {
            jdbc.sql("UPDATE accounts SET balance = 1 WHERE id = :id").param("id", a).update();
        }
        assertApiError("INVALID_AMOUNT", () -> deposit(a, TransferService.MAX_AMOUNT + 1, "USD"));
        assertLedgerHealthy();
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    void accountsOpenedMidMonthStartAtZeroAndQuietAccountsStillGetAStatement() {
        clock.set(Instant.parse("2019-06-15T12:00:00Z"));
        UUID june = fundedAccount("USD", 400);
        UUID quiet = newAccount("USD");                  // never used
        clock.set(Instant.parse("2019-07-10T12:00:00Z"));
        UUID july = fundedAccount("USD", 250);           // opened after June closed

        clock.set(Instant.parse("2019-08-01T00:05:00Z"));
        ReconciliationService.Run juneRun = reconciliation.run(YearMonth.of(2019, 6));
        assertThat(juneRun.statements()).extracting(ReconciliationService.Statement::accountId).doesNotContain(july);
        ReconciliationService.Statement quietJune = statement(juneRun, quiet);
        assertThat(quietJune.entryCount()).isZero();
        assertThat(quietJune.closingBalance()).isZero();

        ReconciliationService.Run julyRun = reconciliation.run(YearMonth.of(2019, 7));
        assertThat(statement(julyRun, july).openingBalance()).isZero();   // absent from June's close => opened at 0
        assertThat(statement(julyRun, june).openingBalance()).isEqualTo(400);
        assertThat(julyRun.discrepancies()).filteredOn(d -> List.of(june, quiet, july).contains(d.accountId())).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private long fxBalance(String currency) {
        return jdbc.sql("SELECT balance FROM accounts WHERE system_role = 'FX' AND currency = :c")
                .param("c", currency).query(Long.class).single();
    }

    private static ReconciliationService.Statement statement(ReconciliationService.Run run, UUID account) {
        return run.statements().stream().filter(s -> s.accountId().equals(account)).findFirst().orElseThrow();
    }

    private static void race(Runnable... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> { start.await(); task.run(); return null; }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
