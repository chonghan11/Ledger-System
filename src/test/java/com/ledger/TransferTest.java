package com.ledger;

import com.ledger.account.AccountService;
import com.ledger.posting.LedgerTransaction;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferTest extends IntegrationTest {

    @Test
    void transferMovesMoneyAtomicallyBetweenAccounts() {
        UUID a = fundedAccount("USD", 10_000);
        UUID b = newAccount("USD");

        LedgerTransaction tx = transfer(a, b, 2_500, "USD");

        assertThat(tx.id()).isNotNull();
        assertThat(balance(a)).isEqualTo(7_500);
        assertThat(balance(b)).isEqualTo(2_500);
        // Double entry: the transaction's entries cancel out.
        Long net = jdbc.sql("SELECT SUM(amount) FROM entries WHERE transaction_id = :id").param("id", tx.id())
                .query(Long.class).single();
        assertThat(net).isZero();
        assertLedgerHealthy();
    }

    @Test
    void insufficientFundsIsRejectedAndNothingChanges() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        long txBefore = countTransactions();

        assertApiError("INSUFFICIENT_FUNDS", () -> transfer(a, b, 1_001, "USD"));

        assertThat(balance(a)).isEqualTo(1_000);
        assertThat(balance(b)).isZero();
        assertThat(countTransactions()).isEqualTo(txBefore);
    }

    @Test
    void canSpendExactlyTheFullBalance() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        transfer(a, b, 1_000, "USD");
        assertThat(balance(a)).isZero();
    }

    @Test
    void rejectsInvalidTransfers() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        UUID sgd = newAccount("SGD");

        assertApiError("SAME_ACCOUNT", () -> transfer(a, a, 10, "USD"));
        assertApiError("INVALID_AMOUNT", () -> transfer(a, b, 0, "USD"));
        assertApiError("INVALID_AMOUNT", () -> transfer(a, b, -5, "USD"));
        assertApiError("CURRENCY_MISMATCH", () -> transfer(a, sgd, 10, "SGD"));   // amount is in source currency
        assertApiError("NOT_FOUND", () -> transfer(a, UUID.randomUUID(), 10, "USD"));
        assertApiError("NOT_FOUND", () -> transfer(UUID.randomUUID(), b, 10, "USD"));

        UUID external = jdbc.sql("SELECT id FROM accounts WHERE system_role = 'EXTERNAL' AND currency = 'USD'")
                .query(UUID.class).single();
        assertApiError("SYSTEM_ACCOUNT", () -> transfer(external, b, 10, "USD"));
        assertApiError("SYSTEM_ACCOUNT", () -> transfer(a, external, 10, "USD"));
        assertThat(balance(a)).isEqualTo(1_000);
    }

    @Test
    void incomingTransferIsHeldUntilTheHoldPeriodEnds() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        UUID c = newAccount("USD");
        transfer(a, b, 600, "USD");

        AccountService.AccountView view = accounts.get(b);
        assertThat(view.balance()).isEqualTo(600);
        assertThat(view.heldBalance()).isEqualTo(600);
        assertThat(view.availableBalance()).isZero();
        assertApiError("INSUFFICIENT_FUNDS", () -> transfer(b, c, 1, "USD"));

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertApiError("INSUFFICIENT_FUNDS", () -> transfer(b, c, 1, "USD"));

        clock.advance(Duration.ofSeconds(1));   // exactly 10 minutes: released
        assertThat(available(b)).isEqualTo(600);
        transfer(b, c, 600, "USD");
        assertThat(balance(b)).isZero();
    }

    @Test
    void senderSeesDebitImmediately() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        transfer(a, b, 400, "USD");
        assertThat(available(a)).isEqualTo(600);   // outgoing money is never "held" for the sender
    }

    @Test
    void depositsAreAvailableImmediatelyAndWithdrawalsCheckFunds() {
        UUID a = newAccount("USD");
        deposit(a, 500, "USD");
        assertThat(available(a)).isEqualTo(500);

        transfers.withdraw(key(), a, 200, "USD", "ATM");
        assertThat(balance(a)).isEqualTo(300);
        assertApiError("INSUFFICIENT_FUNDS", () -> transfers.withdraw(key(), a, 301, "USD", null));
        assertApiError("CURRENCY_MISMATCH", () -> transfers.deposit(key(), a, 1, "SGD", null));
        assertLedgerHealthy();
    }

    @Test
    void historyIsOrderedByTimestampWithRunningBalance() {
        UUID a = newAccount("USD");
        UUID b = newAccount("USD");
        deposit(a, 1_000, "USD");
        clock.advance(Duration.ofMinutes(1));
        LedgerTransaction t1 = transfer(a, b, 300, "USD");
        clock.advance(Duration.ofMinutes(1));
        LedgerTransaction t2 = transfer(a, b, 200, "USD");

        List<AccountService.HistoryItem> history = accounts.history(a);
        assertThat(history).extracting(AccountService.HistoryItem::amount).containsExactly(1_000L, -300L, -200L);
        assertThat(history).extracting(AccountService.HistoryItem::runningBalance).containsExactly(1_000L, 700L, 500L);
        assertThat(history.get(1).transactionId()).isEqualTo(t1.id());
        assertThat(history.get(2).transactionId()).isEqualTo(t2.id());
        assertThat(history.get(1).counterpartyAccountId()).isEqualTo(b);
        assertThat(accounts.history(b)).extracting(AccountService.HistoryItem::counterpartyAccountId).containsOnly(a);
    }

    @Test
    void historyIsStableForEqualTimestamps() {
        // Clock does not move: every entry gets the same timestamp; entry id breaks the tie.
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        for (int i = 1; i <= 5; i++) transfer(a, b, i, "USD");
        assertThat(accounts.history(a)).extracting(AccountService.HistoryItem::amount)
                .containsExactly(1_000L, -1L, -2L, -3L, -4L, -5L);
    }

    @Test
    void journalIsAppendOnly() {
        UUID a = fundedAccount("USD", 1_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE entries SET amount = 1 WHERE account_id = :a").param("a", a).update())
                .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM transactions WHERE destination_account_id = :a").param("a", a).update())
                .hasMessageContaining("append-only");
    }

    private long countTransactions() {
        return jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single();
    }
}
