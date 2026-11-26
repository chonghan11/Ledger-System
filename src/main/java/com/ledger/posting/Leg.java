package com.ledger.posting;

import java.time.Duration;
import java.util.UUID;

/**
 * A requested posting before it is written: "change account X by amount; a credit becomes spendable
 * {@code hold} after the posting time". The posting time itself is assigned by {@link PostingEngine}
 * once all locks are held, and the currency is taken from the locked account.
 */
public record Leg(UUID accountId, long amount, Duration hold) {

    public static Leg debit(UUID accountId, long amount) {
        return new Leg(accountId, Math.negateExact(amount), Duration.ZERO);
    }

    public static Leg credit(UUID accountId, long amount, Duration hold) {
        return new Leg(accountId, amount, hold);
    }

    public static Leg credit(UUID accountId, long amount) {
        return credit(accountId, amount, Duration.ZERO);
    }
}
