package com.ledger.transaction;

import com.ledger.error.ApiException;
import com.ledger.posting.Entry;
import com.ledger.posting.LedgerTransaction;
import com.ledger.posting.Leg;
import com.ledger.posting.PostingEngine;
import com.ledger.posting.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reverses a transaction by posting a NEW transaction whose entries exactly negate the original's.
 * History is never edited.
 *
 * <ul>
 *   <li>Each original entry is negated, so a cross-currency transfer is undone at its ORIGINAL rate:
 *       the sender gets back exactly what they sent, and each FX clearing account returns to exactly
 *       where it was before the transfer, whatever today's rate is.
 *       The reversal header therefore carries no rate of its own (fx_rate_scaled is NULL); the rate
 *       lives on the original transaction it points to.</li>
 *   <li>During the hold period the reversal always succeeds: the credited money was never spendable.</li>
 *   <li>After the hold, it succeeds only if the recipient still has the money available
 *       (otherwise 422 INSUFFICIENT_FUNDS: the ledger never forces a customer negative).</li>
 *   <li>Money returned by a reversal is available immediately: it was the sender's to begin with.</li>
 *   <li>A transaction can be reversed at most once (checked under lock, and UNIQUE(reverses_id) in the DB).
 *       A reversal itself cannot be reversed; post a new transaction instead.</li>
 * </ul>
 */
@Service
public class ReversalService {

    private final TransactionRepository transactions;
    private final PostingEngine engine;

    public ReversalService(TransactionRepository transactions, PostingEngine engine) {
        this.transactions = transactions;
        this.engine = engine;
    }

    @Transactional
    public LedgerTransaction reverse(UUID originalId, String reason) {
        LedgerTransaction original = transactions.findById(originalId)
                .orElseThrow(() -> ApiException.notFound("transaction", originalId));
        if (original.type() == LedgerTransaction.Type.REVERSAL) {
            throw ApiException.unprocessable("CANNOT_REVERSE_REVERSAL",
                    "a reversal cannot itself be reversed; post a new transaction instead");
        }

        List<Entry> originalEntries = transactions.entriesOf(originalId);
        var locked = engine.lockAccounts(originalEntries.stream().map(Entry::accountId).toList());

        // Checked AFTER taking the locks: a concurrent reversal of the same transaction locks the same
        // accounts, so it has either committed already (and we see it here) or is waiting behind us.
        transactions.findReversalOf(originalId).ifPresent(existing -> {
            throw ApiException.conflict("ALREADY_REVERSED",
                    "transaction " + originalId + " was already reversed by " + existing);
        });

        List<Leg> legs = originalEntries.stream()
                .map(e -> new Leg(e.accountId(), Math.negateExact(e.amount()), Duration.ZERO))
                .toList();

        String description = TransferService.normalizeText(reason);
        LedgerTransaction reversal = new LedgerTransaction(
                UUID.randomUUID(), LedgerTransaction.Type.REVERSAL,
                original.destinationAccountId(), original.sourceAccountId(),
                original.destinationAmount(), original.destinationCurrency(),
                original.sourceAmount(), original.sourceCurrency(),
                null,                               // no new rate: the original legs are negated exactly
                original.id(),
                description == null ? "Reversal of " + original.id() : description,
                null);

        return engine.post(locked, reversal, legs, original.id());
    }
}
