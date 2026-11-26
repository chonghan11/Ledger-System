package com.ledger.posting;

import com.ledger.account.Account;
import com.ledger.account.AccountRepository;
import com.ledger.db.Db;
import com.ledger.error.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The only component that writes money. Every operation (deposit, withdrawal, transfer, FX,
 * reversal) is expressed as a list of {@link Leg}s and goes through {@link #post}.
 *
 * <p>Both methods are {@code MANDATORY}: they must run inside the caller's DB transaction,
 * so locking, checking and writing all commit or roll back as one unit.
 */
@Component
public class PostingEngine {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final Clock clock;

    public PostingEngine(AccountRepository accounts, TransactionRepository transactions, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Locks every account in one GLOBAL order (ascending UUID). If transfer A->B and transfer
     * B->A run at the same time, both try to lock the smaller id first, so one simply waits for
     * the other. Without the ordering each could hold one lock while waiting for the other's: a
     * deadlock, which Postgres detects after ~1s by aborting one of them, turning a perfectly valid
     * transfer into a failure. Ordering means that never happens.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockAccounts(Collection<UUID> accountIds) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (UUID id : accountIds.stream().distinct().sorted().toList()) {
            locked.put(id, accounts.lockById(id).orElseThrow(() -> ApiException.notFound("account", id)));
        }
        return locked;
    }

    /**
     * Validates and writes one balanced transaction.
     *
     * <p>The posting time is read HERE, after every lock is held, not when the request arrived. Postings to
     * the same account are serialised by its row lock, so their timestamps follow the order in which they
     * were actually applied: history and month-end buckets can never show a debit "before" the credit that
     * funded it, however long a request waited for a lock.
     *
     * @param locked         accounts already locked via {@link #lockAccounts}; every leg's account must be here
     * @param header         transaction header; its createdAt is replaced by the posting time
     * @param releasesHoldOf when posting a reversal: the original transaction, whose still-held credit is
     *                       being clawed back and therefore must not count as "held" in the funds check
     * @return the header as written, with its posting time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(Map<UUID, Account> locked, LedgerTransaction header, List<Leg> legs,
                                  UUID releasesHoldOf) {
        Instant now = Db.micros(clock.instant());
        LedgerTransaction posted = header.withCreatedAt(now);

        // 1. Structural checks: these indicate a programming error, never bad user input.
        if (legs.size() < 2) throw new IllegalStateException("a transaction needs at least two legs");
        Map<String, Long> netPerCurrency = new TreeMap<>();
        Map<UUID, Long> netPerAccount = new LinkedHashMap<>();
        for (Leg leg : legs) {
            Account account = locked.get(leg.accountId());
            if (account == null) throw new IllegalStateException("leg account not locked: " + leg.accountId());
            if (leg.amount() == 0) throw new IllegalStateException("zero-amount leg");
            if (leg.hold().isNegative()) throw new IllegalStateException("negative hold");
            netPerCurrency.merge(account.currency(), leg.amount(), Math::addExact);
            netPerAccount.merge(account.id(), leg.amount(), Math::addExact);
        }
        // Double-entry invariant: within every currency, debits and credits cancel out exactly.
        netPerCurrency.forEach((currency, net) -> {
            if (net != 0) throw new IllegalStateException("unbalanced transaction in " + currency + ": " + net);
        });

        UUID exclude = releasesHoldOf == null ? AccountRepository.NONE : releasesHoldOf;

        // 2. Funds check (customer accounts only; system accounts may go negative).
        //    Rule: AFTER posting, available = balance - held credits must be >= 0.
        //    For a plain transfer this is "available >= amount"; for a reversal during the hold
        //    period it always passes, because the clawed-back money was never spendable.
        Map<UUID, Long> newBalances = new LinkedHashMap<>();
        netPerAccount.forEach((accountId, net) -> {
            Account account = locked.get(accountId);
            long newBalance;
            try {
                newBalance = Math.addExact(account.balance(), net);   // throws on overflow, never wraps
            } catch (ArithmeticException e) {
                throw ApiException.unprocessable("AMOUNT_OUT_OF_RANGE",
                        "posting would overflow the balance of account " + accountId);
            }
            if (account.isCustomer() && net < 0) {
                long held = accounts.heldCredits(accountId, now, exclude);
                long availableBefore = account.balance() - held;
                if (newBalance - held < 0) {
                    throw ApiException.insufficientFunds(accountId, Math.max(availableBefore, 0), -net);
                }
            }
            newBalances.put(accountId, newBalance);
        });

        // 3. Write: header, entries, cached balances. All inside the caller's DB transaction.
        transactions.insert(posted);
        for (Leg leg : legs) {
            Account account = locked.get(leg.accountId());
            Instant availableAt = leg.amount() > 0 ? now.plus(leg.hold()) : now;
            transactions.insertEntry(posted.id(), account.id(), leg.amount(), account.currency(), availableAt, now);
        }
        newBalances.forEach(accounts::updateBalance);
        return posted;
    }
}
