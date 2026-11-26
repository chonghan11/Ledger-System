package com.ledger.audit;

import com.ledger.db.Db;
import com.ledger.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Month-end reconciliation. For a CLOSED month (UTC), for every account that existed by month end:
 *
 * <pre>
 *   opening = SUM(entries before the month)
 *   closing = opening + credits(month) - debits(month)
 * </pre>
 *
 * The FIRST run of a month is its official close: its statements are frozen and become the reference.
 * Each run cross-checks:
 * <ul>
 *   <li>OPENING_BALANCE_MISMATCH: opening must equal the previous month's officially closed balance.
 *       A difference means history before this month changed after that month was closed.</li>
 *   <li>PERIOD_CHANGED_AFTER_CLOSE: when a month is re-run, its figures must equal its own official close.
 *       Without this, re-running a tampered month would silently "re-freeze" the tampering.</li>
 *   <li>BALANCE_MISMATCH: the account's cached balance must equal the sum of all its entries.</li>
 *   <li>NEGATIVE_CLOSING_BALANCE: a customer account can never close a month below zero.</li>
 *   <li>UNBALANCED_TRANSACTION: every transaction posted in the month balances per currency.</li>
 * </ul>
 * Runs in one REPEATABLE READ snapshot so live traffic does not cause false discrepancies. A month can only be
 * reconciled {@link #CLOSE_GRACE} after it ends, so postings that were still in flight at midnight have committed.
 */
@Service
public class ReconciliationService {

    /** How long after month end a month may be reconciled (also why the scheduler runs at 00:05). */
    public static final Duration CLOSE_GRACE = Duration.ofMinutes(5);

    public record Statement(UUID accountId, String currency, String accountType, long openingBalance,
                            long totalCredits, long totalDebits, long closingBalance, int entryCount, String status) {}

    public record Run(UUID id, String period, Instant runAt, String status, boolean officialClose, int accountsChecked,
                      List<Discrepancy> discrepancies, List<Statement> statements) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public ReconciliationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Run run(YearMonth period) {
        LocalDate startDate = period.atDay(1);
        LocalDate endDate = period.plusMonths(1).atDay(1);
        Instant start = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = endDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant now = Db.micros(clock.instant());
        if (end.plus(CLOSE_GRACE).isAfter(now)) {
            throw ApiException.unprocessable("PERIOD_NOT_CLOSED",
                    "period " + period + " can be reconciled from " + end.plus(CLOSE_GRACE));
        }

        List<Row> rows = jdbc.sql("""
                SELECT a.id, a.currency, a.type, a.balance,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.created_at < :start), 0)                                  AS opening,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.created_at >= :start AND e.created_at < :end AND e.amount > 0), 0) AS credits,
                       COALESCE(-SUM(e.amount) FILTER (WHERE e.created_at >= :start AND e.created_at < :end AND e.amount < 0), 0) AS debits,
                       COUNT(e.id) FILTER (WHERE e.created_at >= :start AND e.created_at < :end)                       AS entry_count,
                       COALESCE(SUM(e.amount), 0)                                                                       AS lifetime
                FROM accounts a
                LEFT JOIN entries e ON e.account_id = a.id
                WHERE a.created_at < :end
                GROUP BY a.id, a.currency, a.type, a.balance
                ORDER BY a.id
                """)
                .param("start", Db.ts(start))
                .param("end", Db.ts(end))
                .query((rs, i) -> new Row(Db.uuid(rs, "id"), rs.getString("currency"), rs.getString("type"),
                        rs.getLong("balance"), rs.getLong("opening"), rs.getLong("credits"), rs.getLong("debits"),
                        rs.getInt("entry_count"), rs.getLong("lifetime")))
                .list();

        Optional<Map<UUID, Frozen>> previousClose = officialClose(period.minusMonths(1));
        Optional<Map<UUID, Frozen>> thisClose = officialClose(period);   // present => this is a re-run

        List<Discrepancy> discrepancies = new ArrayList<>();
        Set<UUID> flagged = new HashSet<>();
        List<Statement> statements = new ArrayList<>();

        for (Row r : rows) {
            long closing = Math.subtractExact(Math.addExact(r.opening, r.credits), r.debits);

            if (previousClose.isPresent()) {
                Frozen prev = previousClose.get().get(r.id);
                long expectedOpening = prev == null ? 0 : prev.closing;   // account unknown last month => opened at 0
                if (expectedOpening != r.opening) {
                    discrepancies.add(new Discrepancy("OPENING_BALANCE_MISMATCH", r.id, null, r.currency,
                            expectedOpening, r.opening, "opening balance differs from the closing balance of "
                            + period.minusMonths(1) + "'s official close; history changed after that month was closed"));
                    flagged.add(r.id);
                }
            }
            if (thisClose.isPresent()) {
                Frozen original = thisClose.get().get(r.id);
                boolean changed = original == null
                        ? r.opening != 0 || r.entryCount != 0
                        : original.opening != r.opening || original.credits != r.credits
                          || original.debits != r.debits || original.entryCount != r.entryCount;
                if (changed) {
                    discrepancies.add(new Discrepancy("PERIOD_CHANGED_AFTER_CLOSE", r.id, null, r.currency,
                            original == null ? 0L : original.closing, closing,
                            "figures for " + period + " differ from its official close; history changed after the month was closed"));
                    flagged.add(r.id);
                }
            }
            if (r.cachedBalance != r.lifetime) {
                discrepancies.add(new Discrepancy("BALANCE_MISMATCH", r.id, null, r.currency, r.lifetime, r.cachedBalance,
                        "cached balance differs from the sum of the account's entries"));
                flagged.add(r.id);
            }
            if ("CUSTOMER".equals(r.type) && closing < 0) {
                discrepancies.add(new Discrepancy("NEGATIVE_CLOSING_BALANCE", r.id, null, r.currency, 0L, closing,
                        "customer account closed the period below zero"));
                flagged.add(r.id);
            }
            statements.add(new Statement(r.id, r.currency, r.type, r.opening, r.credits, r.debits, closing,
                    r.entryCount, null));
        }

        List<Discrepancy> unbalanced = jdbc.sql("""
                SELECT transaction_id, currency, SUM(amount) AS net
                FROM entries
                WHERE created_at >= :start AND created_at < :end
                GROUP BY transaction_id, currency
                HAVING SUM(amount) <> 0
                ORDER BY transaction_id
                """)
                .param("start", Db.ts(start))
                .param("end", Db.ts(end))
                .query((rs, i) -> new Discrepancy("UNBALANCED_TRANSACTION", null, Db.uuid(rs, "transaction_id"),
                        rs.getString("currency"), 0L, rs.getLong("net"), "transaction entries do not sum to zero"))
                .list();
        discrepancies.addAll(unbalanced);
        for (Discrepancy d : unbalanced) {   // mark every account the bad transaction touched
            flagged.addAll(jdbc.sql("SELECT DISTINCT account_id FROM entries WHERE transaction_id = :tx")
                    .param("tx", d.transactionId()).query(UUID.class).list());
        }

        List<Statement> finalStatements = statements.stream()
                .map(s -> new Statement(s.accountId(), s.currency(), s.accountType(), s.openingBalance(), s.totalCredits(),
                        s.totalDebits(), s.closingBalance(), s.entryCount(),
                        flagged.contains(s.accountId()) ? "DISCREPANCY" : "BALANCED"))
                .toList();

        UUID runId = UUID.randomUUID();
        String status = discrepancies.isEmpty() ? "BALANCED" : "DISCREPANCY";
        boolean official = thisClose.isEmpty();
        try {
            persist(runId, startDate, endDate, now, official, status, finalStatements, discrepancies);
        } catch (DuplicateKeyException e) {
            // Another run of the same month became its official close while we were computing. The unique
            // index guarantees there is only ever one; this run rolls back and the caller can simply retry.
            throw ApiException.conflict("RECONCILIATION_IN_PROGRESS",
                    "another run of " + period + " closed it concurrently; retry to compare against that close");
        }
        return new Run(runId, period.toString(), now, status, official, finalStatements.size(),
                discrepancies, finalStatements);
    }

    @Transactional(readOnly = true)
    public Run load(UUID runId) {
        record Header(LocalDate periodStart, boolean official, Instant runAt, String status, int accounts) {}
        Header h = jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id")
                .param("id", runId)
                .query((rs, i) -> new Header(rs.getObject("period_start", LocalDate.class), rs.getBoolean("official_close"),
                        Db.instant(rs, "run_at"), rs.getString("status"), rs.getInt("accounts_checked")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("reconciliation run", runId));

        List<Statement> statements = jdbc.sql("""
                SELECT s.*, a.currency, a.type FROM account_statements s JOIN accounts a ON a.id = s.account_id
                WHERE s.run_id = :id ORDER BY s.account_id
                """)
                .param("id", runId)
                .query((rs, i) -> new Statement(Db.uuid(rs, "account_id"), rs.getString("currency"), rs.getString("type"),
                        rs.getLong("opening_balance"), rs.getLong("total_credits"), rs.getLong("total_debits"),
                        rs.getLong("closing_balance"), rs.getInt("entry_count"), rs.getString("status")))
                .list();

        List<Discrepancy> discrepancies = jdbc.sql("SELECT * FROM reconciliation_discrepancies WHERE run_id = :id ORDER BY id")
                .param("id", runId)
                .query((rs, i) -> new Discrepancy(rs.getString("type"), Db.uuid(rs, "account_id"),
                        Db.uuid(rs, "transaction_id"), rs.getString("currency"), Db.nullableLong(rs, "expected"),
                        Db.nullableLong(rs, "actual"), rs.getString("detail")))
                .list();

        return new Run(runId, YearMonth.from(h.periodStart()).toString(), h.runAt(), h.status(), h.official(),
                h.accounts(), discrepancies, statements);
    }

    /** Statements of a month's official close (its first run), or empty if the month was never reconciled. */
    private Optional<Map<UUID, Frozen>> officialClose(YearMonth period) {
        Optional<UUID> runId = jdbc.sql("""
                SELECT id FROM reconciliation_runs WHERE period_start = :p AND official_close
                """)
                .param("p", period.atDay(1))
                .query(UUID.class)
                .optional();
        if (runId.isEmpty()) return Optional.empty();

        Map<UUID, Frozen> frozen = new HashMap<>();
        jdbc.sql("SELECT * FROM account_statements WHERE run_id = :run")
                .param("run", runId.get())
                .query((rs, i) -> new Frozen(Db.uuid(rs, "account_id"), rs.getLong("opening_balance"),
                        rs.getLong("total_credits"), rs.getLong("total_debits"), rs.getLong("closing_balance"),
                        rs.getInt("entry_count")))
                .list()
                .forEach(f -> frozen.put(f.accountId, f));
        return Optional.of(frozen);
    }

    private void persist(UUID runId, LocalDate start, LocalDate end, Instant now, boolean official, String status,
                         List<Statement> statements, List<Discrepancy> discrepancies) {
        jdbc.sql("""
                INSERT INTO reconciliation_runs (id, period_start, period_end, run_at, official_close, accounts_checked,
                    discrepancy_count, status)
                VALUES (:id, :start, :end, :now, :official, :accounts, :discrepancies, :status)
                """)
                .param("id", runId).param("start", start).param("end", end).param("now", Db.ts(now))
                .param("official", official)
                .param("accounts", statements.size()).param("discrepancies", discrepancies.size()).param("status", status)
                .update();

        for (Statement s : statements) {
            jdbc.sql("""
                    INSERT INTO account_statements (run_id, account_id, period_start, opening_balance, total_credits,
                        total_debits, closing_balance, entry_count, status)
                    VALUES (:run, :account, :start, :opening, :credits, :debits, :closing, :count, :status)
                    """)
                    .param("run", runId).param("account", s.accountId()).param("start", start)
                    .param("opening", s.openingBalance()).param("credits", s.totalCredits())
                    .param("debits", s.totalDebits()).param("closing", s.closingBalance())
                    .param("count", s.entryCount()).param("status", s.status())
                    .update();
        }

        for (Discrepancy d : discrepancies) {
            jdbc.sql("""
                    INSERT INTO reconciliation_discrepancies (run_id, type, account_id, transaction_id, currency,
                        expected, actual, detail)
                    VALUES (:run, :type, :account, :tx, :currency, :expected, :actual, :detail)
                    """)
                    .param("run", runId).param("type", d.type())
                    .param("account", d.accountId(), Types.OTHER)
                    .param("tx", d.transactionId(), Types.OTHER)
                    .param("currency", d.currency(), Types.CHAR)
                    .param("expected", d.expected(), Types.BIGINT)
                    .param("actual", d.actual(), Types.BIGINT)
                    .param("detail", d.detail())
                    .update();
        }
    }

    private record Row(UUID id, String currency, String type, long cachedBalance, long opening,
                       long credits, long debits, int entryCount, long lifetime) {}

    private record Frozen(UUID accountId, long opening, long credits, long debits, long closing, int entryCount) {}
}
