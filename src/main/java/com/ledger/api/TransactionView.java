package com.ledger.api;

import com.ledger.fx.FxMath;
import com.ledger.posting.Entry;
import com.ledger.posting.LedgerTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API shape of a transaction, including its entries (the double-entry legs). */
public record TransactionView(
        UUID id,
        String type,
        String status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long sourceAmount,
        String sourceCurrency,
        long destinationAmount,
        String destinationCurrency,
        String fxRate,
        UUID reversesTransactionId,
        UUID reversedByTransactionId,
        String description,
        Instant createdAt,
        List<EntryView> entries) {

    public record EntryView(UUID accountId, long amount, String currency, Instant availableAt) {}

    public static TransactionView of(LedgerTransaction t, List<Entry> entries, UUID reversedBy) {
        return new TransactionView(
                t.id(), t.type().name(), reversedBy == null ? "POSTED" : "REVERSED",
                t.sourceAccountId(), t.destinationAccountId(),
                t.sourceAmount(), t.sourceCurrency(), t.destinationAmount(), t.destinationCurrency(),
                t.fxRateScaled() == null ? null : FxMath.format(t.fxRateScaled()),
                t.reversesId(), reversedBy, t.description(), t.createdAt(),
                entries.stream().map(e -> new EntryView(e.accountId(), e.amount(), e.currency(), e.availableAt())).toList());
    }
}
