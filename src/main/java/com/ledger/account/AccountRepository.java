package com.ledger.account;

import com.ledger.db.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    /** Used where "no transaction to exclude" is needed, so queries never bind a NULL uuid. */
    public static final UUID NONE = new UUID(0, 0);

    private static final String COLUMNS = "id, name, currency, type, system_role, balance, created_at";

    private static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            Db.uuid(rs, "id"),
            rs.getString("name"),
            rs.getString("currency"),
            Account.Type.valueOf(rs.getString("type")),
            rs.getString("system_role") == null ? null : Account.SystemRole.valueOf(rs.getString("system_role")),
            rs.getLong("balance"),
            Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account a) {
        jdbc.sql("""
                INSERT INTO accounts (id, name, currency, type, system_role, balance, created_at)
                VALUES (:id, :name, :currency, :type, :role, :balance, :createdAt)
                """)
                .param("id", a.id())
                .param("name", a.name())
                .param("currency", a.currency())
                .param("type", a.type().name())
                .param("role", a.systemRole() == null ? null : a.systemRole().name(), Types.VARCHAR)
                .param("balance", a.balance())
                .param("createdAt", Db.ts(a.createdAt()))
                .update();
    }

    /** Plain read, no lock. Fine for display; never use the result to decide a money movement. */
    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Row-level write lock. Any other posting that tries to lock the same row blocks until we commit or
     * roll back, so read-check-write on the balance cannot interleave. Postgres re-reads the latest
     * committed row once the lock is granted.
     *
     * <p>{@code FOR NO KEY UPDATE} rather than {@code FOR UPDATE}: postings only ever change {@code balance},
     * never the key. The weaker mode still excludes every other posting (it conflicts with itself), but it
     * does NOT conflict with the {@code FOR KEY SHARE} lock that foreign-key checks take. With
     * {@code FOR UPDATE}, a month-end reconciliation (REPEATABLE READ) that inserts statements referencing
     * an account failed with "could not serialize access" whenever a transfer to that account committed
     * mid-run. {@code ReconciliationConcurrencyTest} reproduces this.
     */
    public Optional<Account> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public UUID systemAccountId(Account.SystemRole role, String currency) {
        return jdbc.sql("SELECT id FROM accounts WHERE system_role = :role AND currency = :currency")
                .param("role", role.name())
                .param("currency", currency)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("missing " + role + " account for " + currency));
    }

    /** Only ever called while holding the row lock taken by {@link #lockById}. */
    public void updateBalance(UUID id, long newBalance) {
        jdbc.sql("UPDATE accounts SET balance = :balance WHERE id = :id")
                .param("balance", newBalance)
                .param("id", id)
                .update();
    }

    /**
     * Incoming money that is still on hold: positive entries whose available_at is in the
     * future, ignoring entries of transactions that have since been reversed (their money is
     * being clawed back, so it is no longer "held for" this account).
     *
     * @param excludeTransactionId a transaction to ignore as well; used while posting the
     *                             reversal of that transaction (its reversal row does not exist yet)
     */
    public long heldCredits(UUID accountId, Instant now, UUID excludeTransactionId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(e.amount), 0)
                FROM entries e
                WHERE e.account_id = :accountId
                  AND e.amount > 0
                  AND e.available_at > :now
                  AND e.transaction_id <> :exclude
                  AND NOT EXISTS (SELECT 1 FROM transactions r WHERE r.reverses_id = e.transaction_id)
                """)
                .param("accountId", accountId)
                .param("now", Db.ts(now))
                .param("exclude", excludeTransactionId)
                .query(Long.class)
                .single();
    }

    public boolean currencyExists(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM currencies WHERE code = :code)")
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    public int minorUnits(String currency) {
        return jdbc.sql("SELECT minor_units FROM currencies WHERE code = :code")
                .param("code", currency)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("unknown currency " + currency));
    }
}
