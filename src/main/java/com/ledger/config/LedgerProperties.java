package com.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code ledger.*} settings. (The month-end scheduler's {@code ledger.reconciliation.*} settings are read
 * directly by {@code ReconciliationScheduler}, with defaults in its annotations.)
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(Duration holdPeriod) {

    public LedgerProperties {
        if (holdPeriod == null) holdPeriod = Duration.ofMinutes(10);
        if (holdPeriod.isNegative()) throw new IllegalArgumentException("ledger.hold-period must not be negative");
    }
}
