package com.ledger.idempotency;

import com.ledger.db.Db;
import com.ledger.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Makes "send the same request twice" safe.
 *
 * <p>Protocol, all inside the SAME DB transaction as the money movement:
 * <ol>
 *   <li>{@link #claim}: {@code INSERT ... ON CONFLICT DO NOTHING}. If a concurrent request holds the
 *       same key but has not committed, Postgres makes this INSERT wait for it. So of N identical
 *       requests racing, exactly one proceeds; the rest wait, then see its committed result.</li>
 *   <li>The caller posts the transaction.</li>
 *   <li>{@link #complete}: link the key to the transaction id.</li>
 * </ol>
 * If posting fails (e.g. insufficient funds) everything rolls back, including the key, so nothing
 * is recorded and the client may retry the same key once the problem is fixed.
 */
@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_\\-:.]{1,100}");

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return empty if this request owns the key and should execute; otherwise the id of the
     *         transaction a previous identical request already created (replay it, do nothing new)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> claim(String key, String requestHash, Instant now) {
        validateKey(key);
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
                VALUES (:key, :hash, :now)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", Db.ts(now))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }

        StoredKey stored = jdbc.sql("SELECT request_hash, transaction_id FROM idempotency_keys WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, i) -> new StoredKey(rs.getString("request_hash"), Db.uuid(rs, "transaction_id")))
                .single();

        if (!stored.requestHash().equals(requestHash)) {
            // Same key, different request: almost certainly a client bug. Refuse rather than guess.
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key '" + key + "' was already used with a different request");
        }
        if (stored.transactionId() == null) {
            // Cannot normally happen: key and transaction id commit together. Defensive.
            throw ApiException.conflict("REQUEST_IN_PROGRESS", "a request with this Idempotency-Key is still in progress");
        }
        return Optional.of(stored.transactionId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String key, UUID transactionId) {
        jdbc.sql("UPDATE idempotency_keys SET transaction_id = :tx WHERE idempotency_key = :key")
                .param("tx", transactionId)
                .param("key", key)
                .update();
    }

    /** SHA-256 over the operation name and the request's fields, so "same request" means same meaning, not same bytes. */
    public static String hash(String operation, Object... fields) {
        StringBuilder canonical = new StringBuilder(operation);
        for (Object field : fields) {
            canonical.append('|').append(field == null ? "" : field.toString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-100 characters of [A-Za-z0-9_-:.]");
        }
    }

    private record StoredKey(String requestHash, UUID transactionId) {}
}
