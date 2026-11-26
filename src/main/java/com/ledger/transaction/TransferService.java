package com.ledger.transaction;

import com.ledger.account.Account;
import com.ledger.account.AccountRepository;
import com.ledger.config.LedgerProperties;
import com.ledger.db.Db;
import com.ledger.error.ApiException;
import com.ledger.fx.FxMath;
import com.ledger.fx.FxRateService;
import com.ledger.idempotency.IdempotencyService;
import com.ledger.posting.LedgerTransaction;
import com.ledger.posting.Leg;
import com.ledger.posting.PostingEngine;
import com.ledger.posting.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Money movements initiated by clients: transfers (same- or cross-currency), deposits, withdrawals.
 * Each public method is ONE database transaction covering: idempotency claim, account locks,
 * funds check, journal writes and balance updates.
 */
@Service
public class TransferService {

    /**
     * Largest amount accepted in a single request: 10^15 minor units (10 trillion USD). Far above any real
     * payment, and keeps every balance and aggregate comfortably inside a 64-bit integer.
     */
    public static final long MAX_AMOUNT = 1_000_000_000_000_000L;

    /** {@code replayed} = this was a repeat of an earlier request; nothing new was posted. */
    public record Result(LedgerTransaction transaction, boolean replayed) {}

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final PostingEngine engine;
    private final IdempotencyService idempotency;
    private final FxRateService fx;
    private final LedgerProperties props;
    private final Clock clock;

    public TransferService(AccountRepository accounts, TransactionRepository transactions, PostingEngine engine,
                           IdempotencyService idempotency, FxRateService fx, LedgerProperties props, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.engine = engine;
        this.idempotency = idempotency;
        this.fx = fx;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Result transfer(String idempotencyKey, UUID fromId, UUID toId, long amount, String currency, String description) {
        String ccy = normalizeCurrency(currency);
        String desc = normalizeText(description);
        Optional<UUID> previous = claim(idempotencyKey, "TRANSFER", fromId, toId, amount, ccy, desc);
        if (previous.isPresent()) return replay(previous.get());

        requireValidAmount(amount);
        if (fromId.equals(toId)) throw ApiException.unprocessable("SAME_ACCOUNT", "source and destination must differ");

        // Currency and type never change after creation, so an unlocked read is safe here. It only
        // tells us which accounts (including FX clearing accounts) we must lock below.
        Account src = customerAccount(fromId);
        Account dst = customerAccount(toId);
        if (!src.currency().equals(ccy)) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH",
                    "amount currency " + ccy + " does not match source account currency " + src.currency());
        }

        UUID txId = UUID.randomUUID();
        List<Leg> legs = new ArrayList<>();
        LedgerTransaction header;

        if (src.currency().equals(dst.currency())) {
            legs.add(Leg.debit(src.id(), amount));
            legs.add(Leg.credit(dst.id(), amount, props.holdPeriod()));   // incoming transfers are held
            header = new LedgerTransaction(txId, LedgerTransaction.Type.TRANSFER, src.id(), dst.id(),
                    amount, src.currency(), amount, dst.currency(), null, null, desc, null);
        } else {
            // Cross-currency: route through one FX clearing account per currency so that EACH
            // currency still balances to zero on its own:
            //   src  -X (USD)  /  FX_USD +X (USD)       FX_SGD -Y (SGD)  /  dst +Y (SGD)
            FxRateService.Rate rate = fx.rate(src.currency(), dst.currency());
            long converted;
            try {
                converted = FxMath.convert(amount, accounts.minorUnits(src.currency()),
                        accounts.minorUnits(dst.currency()), rate.rateScaled());
            } catch (ArithmeticException e) {
                throw ApiException.unprocessable("AMOUNT_OUT_OF_RANGE", "converted amount is too large");
            }
            if (converted <= 0) {
                throw ApiException.unprocessable("AMOUNT_TOO_SMALL",
                        "amount converts to zero " + dst.currency() + " at rate " + rate.display());
            }
            UUID fxSrc = accounts.systemAccountId(Account.SystemRole.FX, src.currency());
            UUID fxDst = accounts.systemAccountId(Account.SystemRole.FX, dst.currency());
            legs.add(Leg.debit(src.id(), amount));
            legs.add(Leg.credit(fxSrc, amount));
            legs.add(Leg.debit(fxDst, converted));
            legs.add(Leg.credit(dst.id(), converted, props.holdPeriod()));
            header = new LedgerTransaction(txId, LedgerTransaction.Type.TRANSFER, src.id(), dst.id(),
                    amount, src.currency(), converted, dst.currency(), rate.rateScaled(), null, desc, null);
        }

        return post(idempotencyKey, header, legs);
    }

    /** Money entering the ledger from outside. Deposits are spendable immediately (no hold). */
    @Transactional
    public Result deposit(String idempotencyKey, UUID accountId, long amount, String currency, String description) {
        String ccy = normalizeCurrency(currency);
        String desc = normalizeText(description);
        Optional<UUID> previous = claim(idempotencyKey, "DEPOSIT", accountId, amount, ccy, desc);
        if (previous.isPresent()) return replay(previous.get());

        requireValidAmount(amount);
        Account account = customerAccount(accountId);
        requireCurrency(account, ccy);
        UUID external = accounts.systemAccountId(Account.SystemRole.EXTERNAL, account.currency());

        LedgerTransaction header = new LedgerTransaction(UUID.randomUUID(), LedgerTransaction.Type.DEPOSIT,
                external, account.id(), amount, ccy, amount, ccy, null, null, desc, null);
        return post(idempotencyKey, header, List.of(Leg.debit(external, amount), Leg.credit(account.id(), amount)));
    }

    /** Money leaving the ledger. Subject to the same available-balance check as a transfer. */
    @Transactional
    public Result withdraw(String idempotencyKey, UUID accountId, long amount, String currency, String description) {
        String ccy = normalizeCurrency(currency);
        String desc = normalizeText(description);
        Optional<UUID> previous = claim(idempotencyKey, "WITHDRAWAL", accountId, amount, ccy, desc);
        if (previous.isPresent()) return replay(previous.get());

        requireValidAmount(amount);
        Account account = customerAccount(accountId);
        requireCurrency(account, ccy);
        UUID external = accounts.systemAccountId(Account.SystemRole.EXTERNAL, account.currency());

        LedgerTransaction header = new LedgerTransaction(UUID.randomUUID(), LedgerTransaction.Type.WITHDRAWAL,
                account.id(), external, amount, ccy, amount, ccy, null, null, desc, null);
        return post(idempotencyKey, header, List.of(Leg.debit(account.id(), amount), Leg.credit(external, amount)));
    }

    // ------------------------------------------------------------------------------------------

    private Optional<UUID> claim(String key, String operation, Object... fields) {
        Instant now = Db.micros(clock.instant());
        return idempotency.claim(key, IdempotencyService.hash(operation, fields), now);
    }

    private Result post(String idempotencyKey, LedgerTransaction header, List<Leg> legs) {
        var locked = engine.lockAccounts(legs.stream().map(Leg::accountId).toList());
        LedgerTransaction posted = engine.post(locked, header, legs, null);
        idempotency.complete(idempotencyKey, posted.id());
        return new Result(posted, false);
    }

    private Result replay(UUID transactionId) {
        LedgerTransaction tx = transactions.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException("idempotency key points at missing transaction " + transactionId));
        return new Result(tx, true);
    }

    private Account customerAccount(UUID id) {
        Account account = accounts.findById(id).orElseThrow(() -> ApiException.notFound("account", id));
        if (!account.isCustomer()) {
            throw ApiException.unprocessable("SYSTEM_ACCOUNT", "account " + id + " is an internal system account");
        }
        return account;
    }

    private static void requireValidAmount(long amount) {
        if (amount <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "amount must be a positive integer of minor units");
        if (amount > MAX_AMOUNT) throw ApiException.badRequest("INVALID_AMOUNT", "amount must not exceed " + MAX_AMOUNT);
    }

    private static void requireCurrency(Account account, String currency) {
        if (!account.currency().equals(currency)) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH",
                    "currency " + currency + " does not match account currency " + account.currency());
        }
    }

    /** Currency codes are case-insensitive everywhere ("usd" == "USD"). */
    static String normalizeCurrency(String currency) {
        return currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
    }

    /** Blank text is treated as absent, so "" and null are the same request. */
    static String normalizeText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
