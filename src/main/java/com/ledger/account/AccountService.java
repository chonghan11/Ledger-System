package com.ledger.account;

import com.ledger.db.Db;
import com.ledger.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {

    /** Balance view. {@code availableBalance} = what can be spent now; {@code heldBalance} = incoming money still on hold. */
    public record AccountView(UUID id, String name, String currency, Account.Type type, Account.SystemRole systemRole,
                              long balance, long availableBalance, long heldBalance, Instant createdAt) {}

    /** One line of an account's history, with the running balance after it. */
    public record HistoryItem(long entryId, UUID transactionId, String type, long amount, String currency,
                              long runningBalance, UUID counterpartyAccountId, UUID reversesTransactionId,
                              UUID reversedByTransactionId, String description, Instant createdAt, Instant availableAt) {}

    private final AccountRepository accounts;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountService(AccountRepository accounts, JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public AccountView create(String name, String currency) {
        String code = currency.strip().toUpperCase(Locale.ROOT);
        if (!accounts.currencyExists(code)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "currency " + currency + " is not supported");
        }
        // strip() removes Unicode whitespace too (e.g. U+3000), which @NotBlank's trim() does not.
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty()) throw ApiException.badRequest("VALIDATION_FAILED", "name must not be blank");
        Account account = new Account(UUID.randomUUID(), cleanName, code, Account.Type.CUSTOMER, null, 0,
                Db.micros(clock.instant()));
        accounts.insert(account);
        return new AccountView(account.id(), account.name(), account.currency(), account.type(), null,
                0, 0, 0, account.createdAt());
    }

    /**
     * REPEATABLE READ gives both queries (balance, held credits) the same snapshot, so a transfer
     * committing between them cannot produce a nonsensical available balance.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountView get(UUID id) {
        Account a = accounts.findById(id).orElseThrow(() -> ApiException.notFound("account", id));
        long held = accounts.heldCredits(id, clock.instant(), AccountRepository.NONE);
        return new AccountView(a.id(), a.name(), a.currency(), a.type(), a.systemRole(),
                a.balance(), a.balance() - held, held, a.createdAt());
    }

    /**
     * All accounts with their available/held split, customers first. One query computes the held
     * amounts for every account (same rule as {@link AccountRepository#heldCredits}), in one snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AccountView> list() {
        return jdbc.sql("""
                SELECT a.id, a.name, a.currency, a.type, a.system_role, a.balance, a.created_at,
                       COALESCE(h.held, 0) AS held
                FROM accounts a
                LEFT JOIN (
                    SELECT e.account_id, SUM(e.amount) AS held
                    FROM entries e
                    WHERE e.amount > 0
                      AND e.available_at > :now
                      AND NOT EXISTS (SELECT 1 FROM transactions r WHERE r.reverses_id = e.transaction_id)
                    GROUP BY e.account_id
                ) h ON h.account_id = a.id
                ORDER BY a.type, a.created_at, a.id
                """)
                .param("now", Db.ts(clock.instant()))
                .query((rs, i) -> {
                    long balance = rs.getLong("balance");
                    long held = rs.getLong("held");
                    String role = rs.getString("system_role");
                    return new AccountView(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("currency"),
                            Account.Type.valueOf(rs.getString("type")),
                            role == null ? null : Account.SystemRole.valueOf(role),
                            balance, balance - held, held, Db.instant(rs, "created_at"));
                })
                .list();
    }

    public record CurrencyView(String code, int minorUnits) {}

    @Transactional(readOnly = true)
    public List<CurrencyView> currencies() {
        return jdbc.sql("SELECT code, minor_units FROM currencies ORDER BY code")
                .query((rs, i) -> new CurrencyView(rs.getString("code"), rs.getInt("minor_units")))
                .list();
    }

    /** Full history ordered by timestamp, then by entry id (a strict total order even for equal timestamps). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<HistoryItem> history(UUID id) {
        accounts.findById(id).orElseThrow(() -> ApiException.notFound("account", id));
        return jdbc.sql("""
                SELECT e.id, e.transaction_id, t.type, e.amount, e.currency, e.created_at, e.available_at,
                       t.source_account_id, t.destination_account_id, t.reverses_id, t.description,
                       rev.id AS reversed_by,
                       SUM(e.amount) OVER (ORDER BY e.created_at, e.id) AS running_balance
                FROM entries e
                JOIN transactions t ON t.id = e.transaction_id
                LEFT JOIN transactions rev ON rev.reverses_id = t.id
                WHERE e.account_id = :id
                ORDER BY e.created_at, e.id
                """)
                .param("id", id)
                .query((rs, i) -> {
                    long amount = rs.getLong("amount");
                    UUID src = Db.uuid(rs, "source_account_id");
                    UUID dst = Db.uuid(rs, "destination_account_id");
                    UUID counterparty = id.equals(src) ? dst : src;
                    return new HistoryItem(rs.getLong("id"), Db.uuid(rs, "transaction_id"), rs.getString("type"),
                            amount, rs.getString("currency"), rs.getLong("running_balance"), counterparty,
                            Db.uuid(rs, "reverses_id"), Db.uuid(rs, "reversed_by"), rs.getString("description"),
                            Db.instant(rs, "created_at"), Db.instant(rs, "available_at"));
                })
                .list();
    }
}
