package com.ledger.audit;

import java.util.UUID;

/**
 * A single problem found by the integrity check or month-end reconciliation.
 * {@code expected}/{@code actual} are minor units where applicable.
 */
public record Discrepancy(String type, UUID accountId, UUID transactionId, String currency,
                          Long expected, Long actual, String detail) {
}
