package com.ledger;

import com.ledger.audit.Discrepancy;
import com.ledger.audit.ReconciliationService;
import com.ledger.db.Db;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each test uses its own year so tests do not depend on each other's frozen statements.
 * Accounts created by other test classes (in 2026) fall after these periods and are excluded.
 */
class ReconciliationTest extends IntegrationTest {

    @Autowired ReconciliationService reconciliation;

    @Test
    void consecutiveMonthsReconcileAndChainOpeningToPreviousClosing() {
        clock.set(Instant.parse("2025-01-10T09:00:00Z"));
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        transfer(a, b, 300, "USD");

        clock.set(Instant.parse("2025-02-05T09:00:00Z"));
        transfer(a, b, 100, "USD");

        ReconciliationService.Run jan = reconciliation.run(YearMonth.of(2025, 1));
        assertThat(jan.status()).isEqualTo("BALANCED");
        ReconciliationService.Statement aJan = statement(jan, a);
        assertThat(aJan.openingBalance()).isZero();
        assertThat(aJan.totalCredits()).isEqualTo(1_000);
        assertThat(aJan.totalDebits()).isEqualTo(300);
        assertThat(aJan.closingBalance()).isEqualTo(700);
        assertThat(aJan.entryCount()).isEqualTo(2);

        clock.set(Instant.parse("2025-03-01T00:05:00Z"));
        ReconciliationService.Run feb = reconciliation.run(YearMonth.of(2025, 2));
        assertThat(feb.status()).isEqualTo("BALANCED");
        assertThat(statement(feb, a).openingBalance()).isEqualTo(700);   // == January's frozen closing
        assertThat(statement(feb, a).closingBalance()).isEqualTo(600);
        assertThat(statement(feb, b).closingBalance()).isEqualTo(400);

        // A stored run can be read back.
        assertThat(reconciliation.load(feb.id()).statements()).hasSameSizeAs(feb.statements());
    }

    @Test
    void periodCanOnlyBeReconciledAfterItEndsPlusGrace() {
        clock.set(Instant.parse("2024-05-31T23:59:59Z"));
        assertApiError("PERIOD_NOT_CLOSED", () -> reconciliation.run(YearMonth.of(2024, 5)));
        clock.set(Instant.parse("2024-06-01T00:04:59Z"));   // in-flight postings may still be committing
        assertApiError("PERIOD_NOT_CLOSED", () -> reconciliation.run(YearMonth.of(2024, 5)));
        clock.set(Instant.parse("2024-06-01T00:05:00Z"));
        assertThat(reconciliation.run(YearMonth.of(2024, 5)).status()).isNotNull();
    }

    @Test
    void entriesAreBucketedByUtcMonthWithMidnightBelongingToTheNewMonth() {
        clock.set(Instant.parse("2021-03-31T23:59:59.999999Z"));
        UUID a = fundedAccount("USD", 100);               // last microsecond of March
        clock.set(Instant.parse("2021-04-01T00:00:00Z"));
        deposit(a, 7, "USD");                              // first instant of April

        clock.set(Instant.parse("2021-05-01T00:05:00Z"));
        ReconciliationService.Run march = reconciliation.run(YearMonth.of(2021, 3));
        ReconciliationService.Run april = reconciliation.run(YearMonth.of(2021, 4));
        assertThat(statement(march, a).closingBalance()).isEqualTo(100);
        assertThat(statement(april, a).openingBalance()).isEqualTo(100);
        assertThat(statement(april, a).totalCredits()).isEqualTo(7);
        assertThat(april.status()).isEqualTo("BALANCED");
    }

    @Test
    void reRunningAnUnchangedMonthIsBalancedButNotTheOfficialClose() {
        clock.set(Instant.parse("2020-02-10T00:00:00Z"));
        fundedAccount("USD", 500);
        clock.set(Instant.parse("2020-03-02T00:00:00Z"));
        ReconciliationService.Run first = reconciliation.run(YearMonth.of(2020, 2));
        ReconciliationService.Run again = reconciliation.run(YearMonth.of(2020, 2));
        assertThat(first.officialClose()).isTrue();
        assertThat(again.officialClose()).isFalse();
        assertThat(again.status()).isEqualTo("BALANCED");
        assertThat(reconciliation.load(again.id()).officialClose()).isFalse();
    }

    @Test
    void detectsCachedBalanceThatDisagreesWithHistory() {
        clock.set(Instant.parse("2023-03-15T12:00:00Z"));
        UUID a = fundedAccount("USD", 2_000);
        clock.set(Instant.parse("2023-04-02T00:00:00Z"));

        jdbc.sql("UPDATE accounts SET balance = balance - 1 WHERE id = :id").param("id", a).update();
        try {
            ReconciliationService.Run run = reconciliation.run(YearMonth.of(2023, 3));
            assertThat(run.status()).isEqualTo("DISCREPANCY");
            assertThat(run.discrepancies()).anySatisfy(d -> {
                assertThat(d.type()).isEqualTo("BALANCE_MISMATCH");
                assertThat(d.accountId()).isEqualTo(a);
                assertThat(d.expected()).isEqualTo(2_000);
                assertThat(d.actual()).isEqualTo(1_999);
            });
            assertThat(statement(run, a).status()).isEqualTo("DISCREPANCY");
        } finally {
            jdbc.sql("UPDATE accounts SET balance = balance + 1 WHERE id = :id").param("id", a).update();
        }
    }

    @Test
    void detectsHistoryChangedAfterAPeriodWasClosed() {
        clock.set(Instant.parse("2022-07-10T12:00:00Z"));
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");

        clock.set(Instant.parse("2022-08-01T01:00:00Z"));
        assertThat(reconciliation.run(YearMonth.of(2022, 7)).status()).isEqualTo("BALANCED");   // July frozen

        // Someone back-dates a balanced transfer into July AFTER July was closed (bypassing the API).
        UUID rogue = UUID.randomUUID();
        Instant july = Instant.parse("2022-07-20T12:00:00Z");
        jdbc.sql("""
                INSERT INTO transactions (id, type, source_account_id, destination_account_id, source_amount,
                    source_currency, destination_amount, destination_currency, created_at)
                VALUES (:id, 'TRANSFER', :a, :b, 50, 'USD', 50, 'USD', :t)
                """).param("id", rogue).param("a", a).param("b", b).param("t", Db.ts(july)).update();
        insertEntry(rogue, a, -50, july);
        insertEntry(rogue, b, 50, july);
        jdbc.sql("UPDATE accounts SET balance = balance - 50 WHERE id = :id").param("id", a).update();
        jdbc.sql("UPDATE accounts SET balance = balance + 50 WHERE id = :id").param("id", b).update();

        clock.set(Instant.parse("2022-09-01T01:00:00Z"));
        ReconciliationService.Run aug = reconciliation.run(YearMonth.of(2022, 8));

        // Balances and transactions are all internally consistent, yet the change is caught,
        // because August's opening no longer matches July's frozen closing.
        // (Assertions are scoped to this test's accounts: accounts other test methods create with
        //  earlier dates are, correctly, also reported as having appeared after July was closed.)
        assertThat(aug.status()).isEqualTo("DISCREPANCY");
        assertThat(ownDiscrepancies(aug, a, b)).extracting(Discrepancy::type).containsOnly("OPENING_BALANCE_MISMATCH");
        assertThat(ownDiscrepancies(aug, a, b)).extracting(Discrepancy::accountId).containsExactlyInAnyOrder(a, b);
        assertThat(ownDiscrepancies(aug, a, b)).extracting(Discrepancy::currency).containsOnly("USD");
        assertThat(ownDiscrepancies(reconciliation.load(aug.id()), a, b))
                .extracting(Discrepancy::currency).containsOnly("USD");

        // Re-running the tampered month must not quietly "re-freeze" the new figures...
        ReconciliationService.Run julyAgain = reconciliation.run(YearMonth.of(2022, 7));
        assertThat(julyAgain.officialClose()).isFalse();
        assertThat(ownDiscrepancies(julyAgain, a, b)).extracting(Discrepancy::type)
                .containsExactly("PERIOD_CHANGED_AFTER_CLOSE", "PERIOD_CHANGED_AFTER_CLOSE");
        // ...and August still compares against July's OFFICIAL close, so it keeps flagging.
        assertThat(ownDiscrepancies(reconciliation.run(YearMonth.of(2022, 8)), a, b))
                .extracting(Discrepancy::type).containsOnly("OPENING_BALANCE_MISMATCH");
        assertLedgerHealthy();
    }

    private static java.util.List<Discrepancy> ownDiscrepancies(ReconciliationService.Run run, UUID... ids) {
        java.util.Set<UUID> own = java.util.Set.of(ids);
        return run.discrepancies().stream().filter(d -> own.contains(d.accountId())).toList();
    }

    private void insertEntry(UUID tx, UUID account, long amount, Instant at) {
        jdbc.sql("""
                INSERT INTO entries (transaction_id, account_id, amount, currency, available_at, created_at)
                VALUES (:tx, :a, :amount, 'USD', :t, :t)
                """).param("tx", tx).param("a", account).param("amount", amount).param("t", Db.ts(at)).update();
    }

    private static ReconciliationService.Statement statement(ReconciliationService.Run run, UUID account) {
        return run.statements().stream().filter(s -> s.accountId().equals(account)).findFirst().orElseThrow();
    }
}
