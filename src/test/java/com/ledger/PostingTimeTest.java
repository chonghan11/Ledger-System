package com.ledger;

import com.ledger.account.AccountService;
import com.ledger.posting.LedgerTransaction;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The posting time must be taken AFTER the account locks are acquired, not when the request arrived.
 * Otherwise a request that waited behind another could be stamped earlier than a posting that was
 * applied before it, and history / month-end bucketing would show a balance that never existed.
 */
class PostingTimeTest extends IntegrationTest {

    @Autowired DataSource dataSource;

    @Test
    void aTransferThatWaitedForALockIsStampedWhenItIsApplied() throws Exception {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement ps = blocker.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, a);
                ps.executeQuery();
            }

            Instant arrival = clock.instant();
            CompletableFuture<LedgerTransaction> pending = CompletableFuture.supplyAsync(() -> transfer(a, b, 100, "USD"));
            awaitBlockedOnLock();

            clock.advance(Duration.ofMinutes(5));   // time passes while the transfer is queued
            blocker.commit();                       // release the lock

            LedgerTransaction tx = pending.get(30, TimeUnit.SECONDS);
            assertThat(tx.createdAt()).isEqualTo(arrival.plus(Duration.ofMinutes(5)));
        }

        // The hold also starts from the posting time, so it is never shortened by lock waits.
        List<AccountService.HistoryItem> history = accounts.history(b);
        assertThat(history.get(0).availableAt()).isEqualTo(history.get(0).createdAt().plus(Duration.ofMinutes(10)));
    }

    @Test
    void historyNeverShowsADebitBeforeTheCreditThatFundedIt() {
        UUID a = newAccount("USD");
        deposit(a, 100, "USD");
        clock.advance(Duration.ofSeconds(1));
        transfers.withdraw(key(), a, 100, "USD", null);
        assertThat(accounts.history(a)).extracting(AccountService.HistoryItem::runningBalance).containsExactly(100L, 0L);
    }

    private void awaitBlockedOnLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.sql("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE '%FROM accounts%' AND query LIKE '%NO KEY UPDATE%'
                    """).query(Integer.class).single();
            if (waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("transfer never blocked on the row lock");
    }
}
