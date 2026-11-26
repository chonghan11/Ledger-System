package com.ledger.fx;

import com.ledger.db.Db;
import com.ledger.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Exchange-rate table. Rates are directional (USD->SGD and SGD->USD are separate rows), which is
 * how real desks quote them (with a spread). There is no triangulation through a third currency:
 * a missing pair is rejected rather than guessed.
 */
@Service
public class FxRateService {

    public record Rate(String base, String quote, long rateScaled, Instant updatedAt) {
        public String display() { return FxMath.format(rateScaled); }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public FxRateService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Reads the rate inside the caller's transaction so the rate used is the one recorded on the transaction. */
    public Rate rate(String base, String quote) {
        return jdbc.sql("SELECT * FROM exchange_rates WHERE base_currency = :b AND quote_currency = :q")
                .param("b", base)
                .param("q", quote)
                .query((rs, i) -> new Rate(rs.getString("base_currency"), rs.getString("quote_currency"),
                        rs.getLong("rate_scaled"), Db.instant(rs, "updated_at")))
                .optional()
                .orElseThrow(() -> ApiException.unprocessable("FX_RATE_UNAVAILABLE",
                        "no exchange rate configured for " + base + "->" + quote));
    }

    public List<Rate> all() {
        return jdbc.sql("SELECT * FROM exchange_rates ORDER BY base_currency, quote_currency")
                .query((rs, i) -> new Rate(rs.getString("base_currency"), rs.getString("quote_currency"),
                        rs.getLong("rate_scaled"), Db.instant(rs, "updated_at")))
                .list();
    }

    @Transactional
    public Rate upsert(String base, String quote, String rateText) {
        String b = base.strip().toUpperCase(java.util.Locale.ROOT);   // ROOT: no locale-specific case rules
        String q = quote.strip().toUpperCase(java.util.Locale.ROOT);
        if (b.equals(q)) throw ApiException.badRequest("INVALID_RATE", "base and quote currency must differ");
        Boolean known = jdbc.sql("SELECT COUNT(*) = 2 FROM currencies WHERE code IN (:b, :q)")
                .param("b", b).param("q", q).query(Boolean.class).single();
        if (!known) throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "unknown currency in pair " + b + "/" + q);

        long scaled;
        try {
            scaled = FxMath.parse(rateText);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw ApiException.badRequest("INVALID_RATE", e.getMessage());
        }
        Instant now = Db.micros(clock.instant());
        jdbc.sql("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate_scaled, updated_at)
                VALUES (:b, :q, :rate, :now)
                ON CONFLICT (base_currency, quote_currency) DO UPDATE SET rate_scaled = :rate, updated_at = :now
                """)
                .param("b", b).param("q", q).param("rate", scaled).param("now", Db.ts(now))
                .update();
        return new Rate(b, q, scaled, now);
    }
}
