package com.ledger.audit;

import com.ledger.db.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ledger's invariants from first principles and reports every violation.
 *
 * <p>Runs in ONE REPEATABLE READ snapshot. Each check below is a single SQL statement, and Postgres already
 * reads a single statement from one snapshot, so no individual check can be fooled by an in-flight transfer.
 * REPEATABLE READ adds consistency ACROSS the statements: the per-currency totals, the per-account checks and
 * the counts all describe the same instant, so the report is one coherent picture while transfers keep committing.
 *
 * <p>Invariants checked:
 * <ol>
 *   <li>LEDGER_IMBALANCE: per currency, the sum of ALL entries is zero (money is never created or destroyed;
 *       it only enters via the EXTERNAL account, which goes negative by the same amount).</li>
 *   <li>TOTAL_MISMATCH: per currency, the sum of cached account balances is zero, i.e.
 *       customer balances + FX positions == -(EXTERNAL balance) == net money deposited.</li>
 *   <li>BALANCE_MISMATCH: each account's cached balance equals the sum of its entries.</li>
 *   <li>UNBALANCED_TRANSACTION: each transaction's entries sum to zero per currency.</li>
 *   <li>INCOMPLETE_TRANSACTION: each transaction has at least two entries.</li>
 *   <li>CURRENCY_MISMATCH: each entry's currency equals its account's currency.</li>
 * </ol>
 */
@Service
public class IntegrityCheckService {

    public record CurrencySummary(String currency, long sumOfEntries, long customerBalances,
                                  long externalBalance, long fxBalance, long netDeposited) {}

    public record Report(Instant checkedAt, boolean healthy, long accountsChecked, long transactionsChecked,
                         long entriesChecked, List<CurrencySummary> currencies, List<Discrepancy> discrepancies) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public IntegrityCheckService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        List<Discrepancy> found = new ArrayList<>();

        // 1 + 2: per-currency totals. Entries are summed once, in a single pass (not once per currency).
        List<CurrencySummary> currencies = jdbc.sql("""
                WITH entry_totals AS (SELECT currency, SUM(amount) AS total FROM entries GROUP BY currency)
                SELECT c.code,
                       COALESCE(MAX(t.total), 0)                                                     AS sum_entries,
                       COALESCE(SUM(a.balance) FILTER (WHERE a.type = 'CUSTOMER'), 0)                AS customer,
                       COALESCE(SUM(a.balance) FILTER (WHERE a.system_role = 'EXTERNAL'), 0)          AS external,
                       COALESCE(SUM(a.balance) FILTER (WHERE a.system_role = 'FX'), 0)                AS fx
                FROM currencies c
                LEFT JOIN entry_totals t ON t.currency = c.code
                LEFT JOIN accounts a ON a.currency = c.code
                GROUP BY c.code
                ORDER BY c.code
                """)
                .query((rs, i) -> new CurrencySummary(rs.getString("code"), rs.getLong("sum_entries"),
                        rs.getLong("customer"), rs.getLong("external"), rs.getLong("fx"), -rs.getLong("external")))
                .list();

        for (CurrencySummary c : currencies) {
            if (c.sumOfEntries() != 0) {
                found.add(new Discrepancy("LEDGER_IMBALANCE", null, null, c.currency(), 0L, c.sumOfEntries(),
                        "entries in " + c.currency() + " sum to " + c.sumOfEntries() + " instead of 0"));
            }
            long cachedTotal = c.customerBalances() + c.externalBalance() + c.fxBalance();
            if (cachedTotal != 0) {
                found.add(new Discrepancy("TOTAL_MISMATCH", null, null, c.currency(),
                        c.netDeposited(), c.customerBalances() + c.fxBalance(),
                        "customer + FX balances in " + c.currency() + " should equal the net money deposited"));
            }
        }

        // 3: cached balance vs. entries, per account.
        found.addAll(jdbc.sql("""
                SELECT a.id, a.currency, a.balance, COALESCE(SUM(e.amount), 0) AS entries_total
                FROM accounts a
                LEFT JOIN entries e ON e.account_id = a.id
                GROUP BY a.id, a.currency, a.balance
                HAVING a.balance <> COALESCE(SUM(e.amount), 0)
                ORDER BY a.id
                """)
                .query((rs, i) -> new Discrepancy("BALANCE_MISMATCH", Db.uuid(rs, "id"), null, rs.getString("currency"),
                        rs.getLong("entries_total"), rs.getLong("balance"),
                        "cached balance differs from the sum of the account's entries"))
                .list());

        // 4: every transaction balances per currency.
        found.addAll(jdbc.sql("""
                SELECT transaction_id, currency, SUM(amount) AS net
                FROM entries
                GROUP BY transaction_id, currency
                HAVING SUM(amount) <> 0
                ORDER BY transaction_id
                """)
                .query((rs, i) -> new Discrepancy("UNBALANCED_TRANSACTION", null, Db.uuid(rs, "transaction_id"),
                        rs.getString("currency"), 0L, rs.getLong("net"),
                        "transaction entries do not sum to zero"))
                .list());

        // 5: no transaction without its legs.
        found.addAll(jdbc.sql("""
                SELECT t.id, COUNT(e.id) AS n
                FROM transactions t
                LEFT JOIN entries e ON e.transaction_id = t.id
                GROUP BY t.id
                HAVING COUNT(e.id) < 2
                ORDER BY t.id
                """)
                .query((rs, i) -> new Discrepancy("INCOMPLETE_TRANSACTION", null, Db.uuid(rs, "id"), null,
                        2L, rs.getLong("n"), "transaction has fewer than two entries"))
                .list());

        // 6: entries are always in their account's currency.
        found.addAll(jdbc.sql("""
                SELECT e.account_id, e.transaction_id, e.currency, a.currency AS account_currency
                FROM entries e
                JOIN accounts a ON a.id = e.account_id
                WHERE e.currency <> a.currency
                ORDER BY e.id
                """)
                .query((rs, i) -> new Discrepancy("CURRENCY_MISMATCH", Db.uuid(rs, "account_id"),
                        Db.uuid(rs, "transaction_id"), rs.getString("currency"), null, null,
                        "entry currency " + rs.getString("currency") + " on " + rs.getString("account_currency") + " account"))
                .list());

        long accounts = jdbc.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single();
        long transactions = jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single();
        long entries = jdbc.sql("SELECT COUNT(*) FROM entries").query(Long.class).single();

        return new Report(clock.instant(), found.isEmpty(), accounts, transactions, entries, currencies, found);
    }
}
