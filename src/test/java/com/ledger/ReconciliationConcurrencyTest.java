package com.ledger;

import com.ledger.audit.ReconciliationService;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Month-end reconciliation reads in a REPEATABLE READ snapshot and then INSERTs statements that reference
 * accounts by foreign key. A transfer that commits in between updates those account rows. These tests make sure
 * live traffic can never make the reconciliation itself fail.
 */
class ReconciliationConcurrencyTest extends IntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired ReconciliationService reconciliation;

    /** Deterministic reproduction of the exact interleaving, with raw connections. */
    @Test
    void statementInsertSurvivesATransferCommittedAfterTheSnapshot() throws Exception {
        UUID a = fundedAccount("USD", 1_000);
        UUID runId = UUID.randomUUID();

        try (Connection recon = dataSource.getConnection()) {
            recon.setAutoCommit(false);
            recon.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement s = recon.createStatement()) {
                s.execute("SELECT COUNT(*) FROM entries");                 // snapshot taken here
            }

            deposit(a, 5, "USD");                                           // commits: locks + updates account A

            try (PreparedStatement ps = recon.prepareStatement("""
                    INSERT INTO reconciliation_runs (id, period_start, period_end, run_at, accounts_checked,
                        discrepancy_count, status)
                    VALUES (?, DATE '1990-01-01', DATE '1990-02-01', now(), 1, 0, 'BALANCED')
                    """)) {
                ps.setObject(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = recon.prepareStatement("""
                    INSERT INTO account_statements (run_id, account_id, period_start, opening_balance, total_credits,
                        total_debits, closing_balance, entry_count, status)
                    VALUES (?, ?, DATE '1990-01-01', 0, 0, 0, 0, 0, 'BALANCED')
                    """)) {
                ps.setObject(1, runId);
                ps.setObject(2, a);
                ps.executeUpdate();                                         // FK check on the account updated above
            }
            recon.rollback();                                               // leave no trace in the shared DB
        }
    }

    /** The real service, run repeatedly while deposits keep committing to accounts it reconciles. */
    @Test
    void reconciliationCompletesUnderLiveTraffic() throws Exception {
        clock.set(Instant.parse("2018-03-10T00:00:00Z"));
        UUID busy = fundedAccount("USD", 1_000);
        clock.set(Instant.parse("2018-04-01T00:10:00Z"));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger deposits = new AtomicInteger();
        Thread traffic = new Thread(() -> {
            while (!stop.get()) {
                deposit(busy, 1, "USD");
                deposits.incrementAndGet();
            }
        });
        traffic.start();
        try {
            for (int i = 0; i < 20; i++) {
                ReconciliationService.Run run = reconciliation.run(YearMonth.of(2018, 3));
                assertThat(run.statements()).isNotEmpty();
            }
        } finally {
            stop.set(true);
            traffic.join();
        }
        assertThat(deposits.get()).isPositive();
        assertLedgerHealthy();
    }
}
