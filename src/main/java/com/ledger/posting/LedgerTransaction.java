package com.ledger.posting;

import java.time.Instant;
import java.util.UUID;

/**
 * Transaction header: the business event. The money itself lives in its {@link Entry} rows.
 * Amounts are in minor units of their currency. {@code fxRateScaled} is the rate x 10^8
 * (null when both sides share a currency).
 */
public record LedgerTransaction(
        UUID id,
        Type type,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long sourceAmount,
        String sourceCurrency,
        long destinationAmount,
        String destinationCurrency,
        Long fxRateScaled,
        UUID reversesId,
        String description,
        Instant createdAt) {

    public enum Type { DEPOSIT, WITHDRAWAL, TRANSFER, REVERSAL }

    public LedgerTransaction withCreatedAt(Instant postedAt) {
        return new LedgerTransaction(id, type, sourceAccountId, destinationAccountId, sourceAmount, sourceCurrency,
                destinationAmount, destinationCurrency, fxRateScaled, reversesId, description, postedAt);
    }
}
