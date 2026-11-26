package com.ledger.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An account row. {@code balance} is the cached sum of all its entries, in minor units.
 */
public record Account(
        UUID id,
        String name,
        String currency,
        Type type,
        SystemRole systemRole,
        long balance,
        Instant createdAt) {

    public enum Type { CUSTOMER, SYSTEM }

    /** EXTERNAL = money entering/leaving the ledger; FX = per-currency clearing account for conversions. */
    public enum SystemRole { EXTERNAL, FX }

    public boolean isCustomer() {
        return type == Type.CUSTOMER;
    }
}
