package com.ledger;

import com.ledger.audit.Discrepancy;
import com.ledger.audit.IntegrityCheckService;
import com.ledger.db.Db;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integrity check must be clean after normal activity, and must catch corruption that bypasses
 * the application (simulated here with raw SQL). Every corruption is undone at the end so the shared
 * database stays healthy for other tests.
 */
class IntegrityCheckTest extends IntegrationTest {

    @Test
    void healthyAfterNormalActivityAndTotalsMatchNetDeposits() {
        UUID a = fundedAccount("GBP", 5_000);
        UUID b = newAccount("GBP");
        transfer(a, b, 1_200, "GBP");
        transfers.withdraw(key(), a, 300, "GBP", null);

        IntegrityCheckService.Report report = integrity.run();

        assertThat(report.healthy()).isTrue();
        IntegrityCheckService.CurrencySummary gbp = report.currencies().stream()
                .filter(c -> c.currency().equals("GBP")).findFirst().orElseThrow();
        assertThat(gbp.sumOfEntries()).isZero();
        // Total money held by customers (+ FX positions) equals what came in minus what went out.
        assertThat(gbp.customerBalances() + gbp.fxBalance()).isEqualTo(gbp.netDeposited());
    }

    @Test
    void detectsCachedBalanceDrift() {
        UUID a = fundedAccount("USD", 1_000);
        jdbc.sql("UPDATE accounts SET balance = balance + 500 WHERE id = :id").param("id", a).update();
        try {
            IntegrityCheckService.Report report = integrity.run();
            assertThat(report.healthy()).isFalse();
            assertThat(report.discrepancies())
                    .anySatisfy(d -> {
                        assertThat(d.type()).isEqualTo("BALANCE_MISMATCH");
                        assertThat(d.accountId()).isEqualTo(a);
                        assertThat(d.expected()).isEqualTo(1_000);
                        assertThat(d.actual()).isEqualTo(1_500);
                    })
                    .anySatisfy(d -> assertThat(d.type()).isEqualTo("TOTAL_MISMATCH"));
        } finally {
            jdbc.sql("UPDATE accounts SET balance = balance - 500 WHERE id = :id").param("id", a).update();
        }
        assertLedgerHealthy();
    }

    @Test
    void detectsUnbalancedAndIncompleteTransactions() {
        UUID a = fundedAccount("USD", 1_000);
        UUID ext = jdbc.sql("SELECT id FROM accounts WHERE system_role = 'EXTERNAL' AND currency = 'USD'")
                .query(UUID.class).single();
        UUID rogue = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO transactions (id, type, source_account_id, destination_account_id, source_amount,
                    source_currency, destination_amount, destination_currency, created_at)
                VALUES (:id, 'DEPOSIT', :ext, :a, 7, 'USD', 7, 'USD', :now)
                """).param("id", rogue).param("ext", ext).param("a", a).param("now", Db.ts(clock.instant())).update();
        // One-legged posting: money appears from nowhere.
        insertEntry(rogue, a, 7);
        jdbc.sql("UPDATE accounts SET balance = balance + 7 WHERE id = :id").param("id", a).update();
        try {
            IntegrityCheckService.Report report = integrity.run();
            assertThat(report.discrepancies()).extracting(Discrepancy::type)
                    .contains("LEDGER_IMBALANCE", "UNBALANCED_TRANSACTION", "INCOMPLETE_TRANSACTION", "TOTAL_MISMATCH");
            assertThat(report.discrepancies()).filteredOn(d -> d.type().equals("UNBALANCED_TRANSACTION"))
                    .extracting(Discrepancy::transactionId).containsExactly(rogue);
        } finally {
            // History is append-only, so "repair" by posting the missing leg, exactly as an operator would.
            insertEntry(rogue, ext, -7);
            jdbc.sql("UPDATE accounts SET balance = balance - 7 WHERE id = :id").param("id", ext).update();
        }
        assertLedgerHealthy();
    }

    private void insertEntry(UUID tx, UUID account, long amount) {
        jdbc.sql("""
                INSERT INTO entries (transaction_id, account_id, amount, currency, available_at, created_at)
                VALUES (:tx, :a, :amount, 'USD', :now, :now)
                """).param("tx", tx).param("a", account).param("amount", amount)
                .param("now", Db.ts(clock.instant())).update();
    }
}
