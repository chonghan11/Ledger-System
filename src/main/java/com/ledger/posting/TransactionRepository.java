package com.ledger.posting;

import com.ledger.db.Db;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private static final RowMapper<LedgerTransaction> TX_MAPPER = (rs, i) -> new LedgerTransaction(
            Db.uuid(rs, "id"),
            LedgerTransaction.Type.valueOf(rs.getString("type")),
            Db.uuid(rs, "source_account_id"),
            Db.uuid(rs, "destination_account_id"),
            rs.getLong("source_amount"),
            rs.getString("source_currency"),
            rs.getLong("destination_amount"),
            rs.getString("destination_currency"),
            Db.nullableLong(rs, "fx_rate_scaled"),
            Db.uuid(rs, "reverses_id"),
            rs.getString("description"),
            Db.instant(rs, "created_at"));

    private static final RowMapper<Entry> ENTRY_MAPPER = (rs, i) -> new Entry(
            rs.getLong("id"),
            Db.uuid(rs, "transaction_id"),
            Db.uuid(rs, "account_id"),
            rs.getLong("amount"),
            rs.getString("currency"),
            Db.instant(rs, "available_at"),
            Db.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LedgerTransaction t) {
        jdbc.sql("""
                INSERT INTO transactions (id, type, source_account_id, destination_account_id,
                    source_amount, source_currency, destination_amount, destination_currency,
                    fx_rate_scaled, reverses_id, description, created_at)
                VALUES (:id, :type, :src, :dst, :srcAmount, :srcCurrency, :dstAmount, :dstCurrency,
                    :fxRate, :reversesId, :description, :createdAt)
                """)
                .param("id", t.id())
                .param("type", t.type().name())
                .param("src", t.sourceAccountId())
                .param("dst", t.destinationAccountId())
                .param("srcAmount", t.sourceAmount())
                .param("srcCurrency", t.sourceCurrency())
                .param("dstAmount", t.destinationAmount())
                .param("dstCurrency", t.destinationCurrency())
                .param("fxRate", t.fxRateScaled(), Types.BIGINT)
                .param("reversesId", t.reversesId(), Types.OTHER)
                .param("description", t.description(), Types.VARCHAR)
                .param("createdAt", Db.ts(t.createdAt()))
                .update();
    }

    public void insertEntry(UUID transactionId, UUID accountId, long amount, String currency,
                            java.time.Instant availableAt, java.time.Instant createdAt) {
        jdbc.sql("""
                INSERT INTO entries (transaction_id, account_id, amount, currency, available_at, created_at)
                VALUES (:tx, :account, :amount, :currency, :availableAt, :createdAt)
                """)
                .param("tx", transactionId)
                .param("account", accountId)
                .param("amount", amount)
                .param("currency", currency)
                .param("availableAt", Db.ts(availableAt))
                .param("createdAt", Db.ts(createdAt))
                .update();
    }

    public Optional<LedgerTransaction> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transactions WHERE id = :id")
                .param("id", id)
                .query(TX_MAPPER)
                .optional();
    }

    public Optional<UUID> findReversalOf(UUID originalId) {
        return jdbc.sql("SELECT id FROM transactions WHERE reverses_id = :id")
                .param("id", originalId)
                .query(UUID.class)
                .optional();
    }

    public List<Entry> entriesOf(UUID transactionId) {
        return jdbc.sql("SELECT * FROM entries WHERE transaction_id = :tx ORDER BY id")
                .param("tx", transactionId)
                .query(ENTRY_MAPPER)
                .list();
    }
}
