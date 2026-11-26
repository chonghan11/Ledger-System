package com.ledger.posting;

import java.time.Instant;
import java.util.UUID;

/**
 * One posting against one account. Signed: positive increases the balance, negative decreases it.
 * {@code availableAt} is when a positive amount becomes spendable (hold period).
 */
public record Entry(
        long id,
        UUID transactionId,
        UUID accountId,
        long amount,
        String currency,
        Instant availableAt,
        Instant createdAt) {
}
