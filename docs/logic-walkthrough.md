# Ledger logic walkthrough

This is a companion to the README. The README says *what* the system does and *why*. This document traces *exactly how* it does it, with real numbers, so readers can follow every line of the posting path and see why it is correct.

Conventions used below:

- Amounts are shown as `minor units (human form)`, e.g. `2500 (25.00 USD)`.
- An entry's sign is from the account's point of view: positive increases the balance, negative decreases it.
- Code references are `path/File.java:line`, relative to `src/main/java/com/ledger/` unless they start with `src/`.
- The default clock in the tests is `2026-06-15T10:00:00Z`, so the traces use that day too.

---

## 1. The five rules everything rests on

**Rule 1: money is an integer count of minor units.** Every amount is a `long` of cents (or yen, for JPY), stored as `BIGINT`. Decimal places per currency live in the `currencies` table (`src/main/resources/db/migration/V1__ledger_schema.sql:7-13`). Jackson refuses `12.5` and `"100"` instead of truncating or coercing them (`src/main/resources/application.yml:15` and `:19`). Balance arithmetic uses `Math.addExact`, which throws on overflow instead of wrapping to a negative number (`posting/PostingEngine.java:104`). The only place a fraction can appear is FX conversion, which uses `BigInteger` and rounds exactly once (`fx/FxMath.java:28-40`).

**Rule 2: double-entry, per currency.** A transaction is two or more entries, and within each currency its entries sum to exactly zero. The engine checks this before it writes anything (`posting/PostingEngine.java:76-91`). If the check fails, it throws `IllegalStateException`: that is a programming error, not bad input, so it becomes a 500 and the transaction rolls back. Money from outside the ledger comes through one `EXTERNAL` account per currency. Conversions go through one `FX` clearing account per currency (`V1__ledger_schema.sql:35-45`). Only customer accounts are constrained to be non-negative (`V1__ledger_schema.sql:31-32`); system accounts are allowed to go negative.

**Rule 3: entries are the truth; `accounts.balance` is a cache.** The cached balance is written in the same database transaction as the entries (`posting/PostingEngine.java:126`), and only by a session that holds that account's row lock (`account/AccountRepository.java:87-93`). The cache exists so the funds check reads one row instead of summing a whole history. Because it is a cache, the integrity check recomputes it from the entries.

**Rule 4: history is append-only.** Triggers reject every `UPDATE` and `DELETE` on `entries` and `transactions` (`V1__ledger_schema.sql:100-112`). A mistake is fixed by posting a new `REVERSAL` transaction that points back with `reverses_id`, and `UNIQUE(reverses_id)` lets each transaction be reversed at most once (`V1__ledger_schema.sql:62`). The `accounts` table is *not* append-only, because its cached balance changes on every posting.

**Rule 5: available = balance − held.** "Held" means the positive entries on the account whose `available_at` is still in the future, excluding any transaction that has since been reversed (`account/AccountRepository.java:103-118`). Only credits from transfers get a hold (`transaction/TransferService.java:87`, `:111`). Debits, deposits and reversal credits have `available_at = now` (`posting/PostingEngine.java:123`). The funds rule is a single sentence: *after posting, `balance − held ≥ 0` for every customer account being debited* (`posting/PostingEngine.java:109-115`). Every debit enforces it, so a customer's available balance never drops below zero. Section 2.5 relies on this.

---

## 2. Each operation, traced with real numbers

Every money movement runs through the same pipeline, inside one `@Transactional` service method:

1. Claim the idempotency key (`transaction/TransferService.java:66`, `:157-160`).
2. Validate the input: amount, same account, currency, system accounts (`TransferService.java:69-79`).
3. Build the legs.
4. Lock every account involved, in ascending id order (`TransferService.java:163` → `posting/PostingEngine.java:48-54`).
5. Read the clock. This is the posting time (`PostingEngine.java:73`).
6. Run the structural and balance checks (`PostingEngine.java:76-91`).
7. Run the funds check (`PostingEngine.java:95-117`).
8. Insert the header, insert the entries, update the cached balances (`PostingEngine.java:119-126`).
9. Link the key to the transaction id (`TransferService.java:165`).
10. Commit when the method returns. Any exception rolls back all of the above.

The running example starts from a fresh ledger with three customers: Alice (USD), Bob (USD) and Sam (SGD).

### 2.1 Deposit: 100.00 USD to Alice at 10:00:00

`TransferService.deposit` (`transaction/TransferService.java:121-135`) looks up `EXTERNAL_USD` (`:130`), builds a header with source = EXTERNAL and destination = Alice (`:132-133`), and posts two legs (`:134`). Neither leg has a hold.

| Account | Entry | Balance before | Balance after | available_at |
|---|---|---|---|---|
| EXTERNAL_USD | `-10000 (-100.00 USD)` | `0` | `-10000 (-100.00 USD)` | 10:00:00 |
| Alice | `+10000 (100.00 USD)` | `0` | `10000 (100.00 USD)` | 10:00:00 |

USD net: −10000 + 10000 = 0. `EXTERNAL_USD` is negative by exactly the amount customers have deposited, which is what makes "total money" checkable later. Both accounts are locked, so every deposit in a currency queues on that currency's EXTERNAL row.

### 2.2 Same-currency transfer: Alice → Bob, 25.00 USD at 10:01:00

The same-currency branch (`TransferService.java:85-89`) creates a debit with no hold (`:86`) and a credit with `props.holdPeriod()` (`:87`). The hold is 10 minutes (`application.yml:27`). In the funds check, Alice's net is −2500 and her held amount is 0, so 7500 − 0 ≥ 0 and the check passes. Bob's net is positive, so he is not checked.

| Account | Entry | Balance before | Balance after | Held after | Available after | available_at |
|---|---|---|---|---|---|---|
| Alice | `-2500 (-25.00 USD)` | `10000 (100.00)` | `7500 (75.00)` | `0` | `7500 (75.00)` | 10:01:00 |
| Bob | `+2500 (25.00 USD)` | `0` | `2500 (25.00)` | `2500 (25.00)` | `0` | **10:11:00** |

The sender loses the money immediately. The receiver sees it in `balance` but cannot spend it until 10:11:00. `available_at` is set from the posting time, not the arrival time (`PostingEngine.java:123`).

### 2.3 FX transfer: Alice (USD) → Sam (SGD), 25.00 USD at 1.345, at 10:02:00

The rate row stores `134500000`, which is 1.345 × 10⁸ (`V1__ledger_schema.sql:141`). `FxMath.convert(2500, 2, 2, 134500000)` (`fx/FxMath.java:24-41`) works like this:

```
numerator   = 2500 × 134500000 × 10^2  = 33,625,000,000,000
denominator = 10^8 × 10^2              = 10,000,000,000
quotient    = 3362,  remainder = 5,000,000,000
2 × remainder == denominator  -> exact tie (33.625)
quotient 3362 is even         -> do not round up      (FxMath.java:36-39)
result      = 3362 (33.62 SGD)
```

Half-up rounding would give 33.63. Half-even sends ties to the even neighbour, so across many conversions the rounding errors cancel out instead of all pushing upward.

Then `TransferService.java:106-111` builds four legs:

| Account | Currency | Entry | Balance before | Balance after | available_at |
|---|---|---|---|---|---|
| Alice | USD | `-2500 (-25.00 USD)` | `7500 (75.00)` | `5000 (50.00)` | 10:02:00 |
| FX_USD | USD | `+2500 (25.00 USD)` | `0` | `2500 (25.00)` | 10:02:00 |
| FX_SGD | SGD | `-3362 (-33.62 SGD)` | `0` | `-3362 (-33.62)` | 10:02:00 |
| Sam | SGD | `+3362 (33.62 SGD)` | `0` | `3362 (33.62)` | **10:12:00** (held) |

USD nets to −2500 + 2500 = 0 and SGD nets to −3362 + 3362 = 0, so each currency balances on its own. The header records `source_amount 2500 USD`, `destination_amount 3362 SGD` and `fx_rate_scaled 134500000` (`TransferService.java:112-113`). The schema requires a rate exactly when the two currencies differ (`V1__ledger_schema.sql:69-71`). All four accounts are locked together, in one sorted order. If the result had been 0, the request would have been rejected with `AMOUNT_TOO_SMALL` (`TransferService.java:102-105`).

### 2.4 Withdrawal: Alice withdraws 10.00 USD at 10:03:00 (and Bob tries)

The withdrawal is a deposit mirrored: header source = Alice, destination = EXTERNAL (`TransferService.java:150-152`).

| Account | Entry | Balance before | Balance after |
|---|---|---|---|
| Alice | `-1000 (-10.00 USD)` | `5000 (50.00)` | `4000 (40.00)` |
| EXTERNAL_USD | `+1000 (10.00 USD)` | `-10000 (-100.00)` | `-9000 (-90.00)` |

At the same moment Bob tries to withdraw `1 (0.01 USD)`. His new balance would be 2499 and his held amount is 2500, so 2499 − 2500 = −1 < 0. The result is `422 INSUFFICIENT_FUNDS` with "available 0" (`PostingEngine.java:112-113`). Nothing is written, because the whole transaction rolls back.

Here is the ledger at 10:03, checked by hand:

| Currency | Accounts | Sum |
|---|---|---|
| USD | Alice 4000 + Bob 2500 + FX_USD 2500 + EXTERNAL_USD −9000 | 0 |
| SGD | Sam 3362 + FX_SGD −3362 + EXTERNAL_SGD 0 | 0 |

### 2.5 Reversal during the hold: undo Alice → Bob at 10:05:00

`ReversalService.reverse` (`transaction/ReversalService.java:45-80`) runs these steps:

1. Load the original transaction and reject it if it is itself a `REVERSAL` (`:47-52`).
2. Read its entries (`:54`) and lock those accounts, sorted (`:55`).
3. *After* locking, check whether a reversal already exists (`:59-62`).
4. Negate every entry, with no hold (`:64-66`).
5. Build a header with source and destination swapped, `fx_rate_scaled = null` and `reverses_id = original` (`:69-77`).
6. Post it with `releasesHoldOf = original.id()` (`:79`).

Funds check for Bob, the account being debited: his balance is 2500. `heldCredits(Bob, now, exclude = original)` returns **0**, because the excluded transaction is skipped (`account/AccountRepository.java:110`). His new balance is 0, and 0 − 0 ≥ 0, so the check passes.

| Account | Entry | Balance before | Balance after | Held after | Available after |
|---|---|---|---|---|---|
| Bob | `-2500 (-25.00 USD)` | `2500 (25.00)` | `0` | `0` | `0` |
| Alice | `+2500 (25.00 USD)` | `4000 (40.00)` | `6500 (65.00)` | `0` | `6500 (65.00)` |

**Why `releasesHoldOf` is needed.** Inside the funds check, the reversal row has not been inserted yet. The `NOT EXISTS (... reverses_id = e.transaction_id)` clause (`AccountRepository.java:111`) therefore cannot see it. Without the explicit exclusion, Bob's held amount would be 2500, and 0 − 2500 < 0 would wrongly reject the reversal. After commit, the `NOT EXISTS` clause takes over, which is why the reversed credit stops counting as held in later reads.

**Why it always succeeds.** Let B be Bob's balance, H his held amount, and X the reversed amount (which is included in H). After the reversal, `(B − X) − (H − X) = B − H`, which is his available balance before the reversal. Rule 5 guarantees that is ≥ 0.

Reversing the FX transfer during its hold works the same way, with all four legs negated: Sam −3362, FX_SGD +3362, FX_USD −2500, Alice +2500. All four accounts return exactly to their earlier balances, whatever the current rate.

### 2.6 Reversal after the hold

Once the hold has expired, the original credit no longer counts as held, so the exclusion changes nothing. The recipient simply needs X available.

**Success** (`ReversalTest.afterHoldReversalSucceedsIfMoneyIsStillThere`): A has 1000. A → B 500 at 10:00. The reversal runs at 12:00.

| Account | Entry | Before | After | Check |
|---|---|---|---|---|
| B | `-500 (-5.00 USD)` | `500` | `0` | held 0; 0 − 0 ≥ 0, pass |
| A | `+500 (5.00 USD)` | `500` | `1000 (10.00)` | credit, not checked |

**Rejection** (`ReversalTest.afterHoldReversalFailsIfRecipientAlreadySpentTheMoney`): A → B 500 at 10:00. At 10:11, B → C 450. Then the reversal is attempted.

| Account | Would-be entry | Before | Would-be after | Check |
|---|---|---|---|---|
| B | `-500 (-5.00 USD)` | `50 (0.50)` | `-450` | held 0; −450 < 0, **422 INSUFFICIENT_FUNDS** ("available 50, 500 required") |

Everything rolls back: A stays at 500 and B at 50. The ledger never forces a customer negative. A real system would recover the money outside the ledger (README §7).

### 2.7 Idempotent replay

Same request twice: key `K`, transfer A → B of `100 (1.00 USD)`, description "rent" (`IdempotencyTest.repeatingARequestReturnsTheOriginalTransactionAndMovesMoneyOnce`).

The request hash is SHA-256 of `TRANSFER|<A>|<B>|100|USD|rent` (`idempotency/IdempotencyService.java:90-101`). Currency is upper-cased and blank text is treated as null *before* hashing (`TransferService.java:64-65`, `:196-203`).

| Step | First call | Second call |
|---|---|---|
| `INSERT ... ON CONFLICT DO NOTHING` (`IdempotencyService.java:51-59`) | 1 row inserted, so this call executes (`:60-62`) | 0 rows |
| Read the stored row (`:64-67`) | n/a | hash equal, `transaction_id = T1` |
| Post | yes: A 1000 → 900, B 0 → 100 | **no** |
| `complete()` sets `transaction_id = T1` (`:82-87`) | yes | n/a |
| Response (`api/TransactionController.java:60-66`) | 201, `Idempotent-Replayed: false` | 201, same T1, `Idempotent-Replayed: true` |

The other outcomes:

- **Same key, different body** (for example amount 999): the hashes differ, so the call is refused with `422 IDEMPOTENCY_KEY_REUSED` (`IdempotencyService.java:69-73`).
- **First call failed** (for example insufficient funds): the key row is rolled back with everything else. A later call with the same key executes normally.
- **`transaction_id` is NULL**: this cannot happen, because the key and the transaction id commit together. The code still handles it defensively with `409 REQUEST_IN_PROGRESS` (`:74-77`).

### 2.8 The reversal chain A → B → C

From `EdgeCaseLogicTest.reversalChainUnwindsInTheRightOrder`:

| Time | Action | A | B (held) | C (held) | Result |
|---|---|---|---|---|---|
| 10:00 | deposit 500 to A | `500` | `0` | `0` | |
| 10:00 | T1: A → B 500 | `0` | `500 (500 held to 10:10)` | `0` | |
| 10:11 | T2: B → C 500 | `0` | `0` | `500 (500 held to 10:21)` | B's hold has expired, so B can spend |
| 10:11 | reverse T1 | | | | **422**: B has 0, needs 500 |
| 10:11 | reverse T2 | `0` | `500 (0 held)` | `0` | succeeds: C's credit is still held (section 2.5) |
| 10:11 | reverse T1 again | `500` | `0` | `0` | succeeds: B has 500 available |

The key point: reversals unwind in reverse order. B gets the T2 money back available immediately, because a reversal credit has no hold (`ReversalService.java:65`). That is what lets T1 be undone straight afterwards.

---

## 3. Concurrency, in plain words

### What goes wrong without locks

Alice has `5000 (50.00 USD)`. Two transfers of `4000 (40.00)` arrive at the same moment.

| Step | T1 | T2 |
|---|---|---|
| read balance | 5000 | 5000 |
| funds check | 5000 − 4000 ≥ 0, pass | 5000 − 4000 ≥ 0, pass |
| insert entry | −4000 | −4000 |
| `UPDATE accounts SET balance = 1000` | writes 1000 | writes 1000 |

Both commit. The entries say Alice is at −3000. The cache says 1000. She spent 80.00 out of 50.00, and the cache hides it. The `balance >= 0` constraint does not fire, because the value it sees (1000) is not negative. The cache is wrong, not negative. This is the classic lost update.

### How row locks fix it

`lockById` is `SELECT ... FOR NO KEY UPDATE` (`account/AccountRepository.java:71-76`). T2 blocks on that line until T1 commits. Postgres then hands T2 the *latest committed* row, so T2 sees balance 1000. 1000 − 4000 < 0, so T2 gets 422. Read, check and write can no longer interleave for the same account. The held-credit query also runs under this lock. Any posting that would add a held credit or a reversal for this account has to lock the same row, so the held amount cannot change between the check and the write.

`FOR NO KEY UPDATE` conflicts with itself, so postings to one account exclude each other exactly as they would under `FOR UPDATE`. What it does *not* conflict with is `FOR KEY SHARE`, the lock a foreign-key check takes on the row it references. Postings only ever change `balance`, never the key, so the weaker mode is the accurate one (Javadoc at `AccountRepository.java:59-70`).

> **Bug found during review.** The lock used to be `FOR UPDATE`. Month-end reconciliation runs in REPEATABLE READ and then inserts `account_statements` rows, each with a foreign key to `accounts`, so each insert takes `FOR KEY SHARE` on an account row. If a transfer to that account committed after the reconciliation's snapshot, that change had been made under `FOR UPDATE`, which conflicts with `FOR KEY SHARE`. A REPEATABLE READ transaction cannot wait out a conflicting change it cannot see, so Postgres aborted the whole run with "could not serialize access due to concurrent update". Any busy account could make month-end fail. A logic audit found it, and `ReconciliationConcurrencyTest` proves the fix two ways: a deterministic raw-JDBC reproduction of the exact interleaving, and the real service run 20 times while deposits keep committing to an account it reconciles.

### How sorting prevents deadlock

T1 is A → B and T2 is B → A, with id(A) < id(B). If each transaction locked its own source first, T1 would hold A and wait for B while T2 held B and waited for A. Postgres would detect the cycle (after `deadlock_timeout`, 1 s by default) and abort one of them. That error would reach the client as `409 CONCURRENCY_CONFLICT` (`error/GlobalExceptionHandler.java:83-87`).

`lockAccounts` sorts the distinct ids first (`posting/PostingEngine.java:50`), so both transactions lock A first. T2 waits at A while holding nothing, and T1 finishes. A cycle needs someone holding a larger id while waiting for a smaller one, and sorted acquisition makes that impossible. The four FX accounts are sorted together, and reversals use the same method (`ReversalService.java:55`). The only lock taken before the account locks is the idempotency key, and every transaction takes that one first, so the order stays the same everywhere. `ConcurrencyTest.opposingTransfersDoNotDeadlock` runs 200 opposing transfers to prove it.

### Why the clock is read after locking

The posting time is read at `PostingEngine.java:73`, after every lock is held, not when the request arrives.

Suppose account X sorts before account A. Transfer W, `100 (1.00 USD)` from A to X, arrives at 23:59:59.000 on the 31st while A's balance is 0. W blocks on X's lock, which another long transaction holds. Meanwhile deposit D (+100 to A) arrives at 00:00:00.500 on the 1st, locks A and commits. W then gets its locks and passes the funds check *only because of D*.

If W kept its arrival time, its debit would be stamped before D's credit. History would show a running balance of −1.00 that never existed. The debit would also land in the old month with nothing to fund it, so that month's reconciliation would report a false `NEGATIVE_CLOSING_BALANCE`. Stamped after locking, W is ordered after D.

The same rule protects the hold. `available_at = posting time + 10 min`, so time spent waiting for a lock cannot shorten the hold. `PostingTimeTest.aTransferThatWaitedForALockIsStampedWhenItIsApplied` holds a lock with raw JDBC, advances the clock 5 minutes, releases the lock, and checks the stamp is `arrival + 5 min`.

### How the idempotency-key race is refereed

Twenty identical requests arrive at once (`IdempotencyTest.concurrentDuplicatesProduceExactlyOneTransaction`). The first `INSERT` creates an uncommitted row for key K. The other 19 inserts conflict with that uncommitted row on the primary key, and Postgres makes each one *wait* for the first transaction to end.

- **If it commits**, each waiter gets "0 rows", reads the committed `transaction_id`, and replays it.
- **If it rolls back**, one waiter's insert succeeds and that request executes.

There is no application lock and no "check, then insert" gap. The primary key is the referee. Because the claim happens before any account lock, duplicates queue on the key row and never touch account rows.

---

## 4. The checks

### 4.1 Integrity check (`audit/IntegrityCheckService.java`)

| Invariant | Query | Catches |
|---|---|---|
| `LEDGER_IMBALANCE` | per currency, `SUM(entries) = 0`; entries are summed once, in a single pass, by the `WITH entry_totals` CTE (`:55-77`) | money created or destroyed |
| `TOTAL_MISMATCH` | per currency, customer + EXTERNAL + FX cached balances = 0, i.e. customer + FX = −EXTERNAL = net deposits (same query; check at `:78-83`) | cache totals disagree with "money that came in" |
| `BALANCE_MISMATCH` | each account's `balance = SUM(its entries)` (`:86-98`) | cache drift on one account |
| `UNBALANCED_TRANSACTION` | each transaction nets 0 per currency (`:100-111`) | a bad posting |
| `INCOMPLETE_TRANSACTION` | each transaction has ≥ 2 entries (`:113-124`) | a header without its legs |
| `CURRENCY_MISMATCH` | entry currency = account currency (`:126-137`) | a USD entry on an SGD account |

**Worked example** (`IntegrityCheckTest.detectsUnbalancedAndIncompleteTransactions`). Raw SQL inserts a DEPOSIT header and a single entry of `+7 (0.07 USD)` on account A, and bumps A's cached balance by 7 to match. The check reports:

- `LEDGER_IMBALANCE`: USD sums to +7.
- `UNBALANCED_TRANSACTION`: the rogue transaction nets +7.
- `INCOMPLETE_TRANSACTION`: it has one entry.
- `TOTAL_MISMATCH`: cached USD totals are +7.

`BALANCE_MISMATCH` does *not* fire, because the forger kept A's cache consistent. This is why several independent checks matter. The test then "repairs" the ledger the append-only way, by inserting the missing −7 leg on EXTERNAL.

**Why REPEATABLE READ** (`:51`, and the class Javadoc at `:17-20`). The whole report reads from one snapshot, so its totals, discrepancies and counts describe the same instant while transfers keep committing. Be precise if asked: each check is a single SQL statement, and Postgres already gives one statement a consistent snapshot even under READ COMMITTED. What REPEATABLE READ adds is consistency *across* the statements. Without it, the counts could include a transfer that the totals missed, and any later change that split one check into two queries would start producing false alarms. It matters even more in `AccountService.get` (`account/AccountService.java:58-64`), where balance and held amount are two separate queries. A commit between them could produce a negative "available".

### 4.2 Month-end reconciliation (`audit/ReconciliationService.java`)

For every account created before the month's end (`:90`), one query computes the opening balance (entries before the start), credits and debits within `[start, end)`, the entry count, and the lifetime sum (`:81-99`). Closing is `opening + credits − debits` (`:109`).

- **Official close.** The first run of a month is that month's official close. A run is stored with `official_close = true` only if the month had no official close yet (`:175`, `:177`), and the lookup selects the run by that flag (`:219-238`). The partial unique index `reconciliation_one_official_close ON reconciliation_runs (period_start) WHERE official_close` allows at most one per month (`V1__ledger_schema.sql:161`, `:171`). If two first runs of a month race, both compute as the official close, but the second insert hits the index. `run` catches the `DuplicateKeyException` and returns `409 RECONCILIATION_IN_PROGRESS` (`:176-183`); a retry is then compared against the winner. `run_seq` still exists, but only to order runs (`V1__ledger_schema.sql:160`, `:170`).
- **Opening chained to the previous close.** If last month has an official close, each account's opening must equal its frozen closing balance there. An account missing from that close must open at 0 (`:111-120`). If last month was never reconciled, this check is skipped.
- **Re-run detection.** If this month already has an official close, opening, credits, debits and entry count must all match it (`:121-133`). Otherwise the run reports `PERIOD_CHANGED_AFTER_CLOSE`. A re-run never becomes the reference, because it is stored with `official_close = false` (`:175`) and the lookup only ever selects the flagged run (`:222`).
- **Five-minute grace.** The run is rejected while `now < monthEnd + 5 min` (`:53`, `:76-79`). A posting stamped 23:59:59.9 may still be committing at midnight. The scheduler runs at 00:05 for the same reason.

**Worked example: normal chaining** (`ReconciliationTest.consecutiveMonthsReconcileAndChainOpeningToPreviousClosing`). On Jan 10, 2025, A gets a deposit of 1000 and sends 300 to B. On Feb 5, A sends 100 more.

| Run | Account | Opening | Credits | Debits | Closing | Entries |
|---|---|---|---|---|---|---|
| 2025-01 | A | `0` | `1000 (10.00)` | `300 (3.00)` | `700 (7.00)` | 2 |
| 2025-01 | B | `0` | `300 (3.00)` | `0` | `300 (3.00)` | 1 |
| 2025-02 | A | `700` = Jan close | `0` | `100 (1.00)` | `600 (6.00)` | 1 |
| 2025-02 | B | `300` = Jan close | `100 (1.00)` | `0` | `400 (4.00)` | 1 |

**Worked example: tampering** (`ReconciliationTest.detectsHistoryChangedAfterAPeriodWasClosed`). July 2022 is closed with A = 1000 and B = 0. Afterwards, raw SQL back-dates a balanced transfer of 50 from A to B into July 20 and adjusts both caches to match. The integrity check stays **green**, because every invariant holds. Reconciliation still catches it:

| Run | Account | Expected (from July's official close) | Recomputed | Discrepancy |
|---|---|---|---|---|
| 2022-08 | A | opening `1000` | opening `950` | `OPENING_BALANCE_MISMATCH` |
| 2022-08 | B | opening `0` | opening `50` | `OPENING_BALANCE_MISMATCH` |
| 2022-07 re-run | A | debits 0, 1 entry | debits 50, 2 entries | `PERIOD_CHANGED_AFTER_CLOSE` |
| 2022-07 re-run | B | credits 0, 0 entries | credits 50, 1 entry | `PERIOD_CHANGED_AFTER_CLOSE` |

Running August again still compares against July's *first* run, so re-running July cannot launder the change.

---

## 5. Edge-case table

| Scenario | Expected result | Why | Test |
|---|---|---|---|
| A has 1300 with 300 held; sends 1001, then 1000 | 1001 rejected; 1000 succeeds; A left at 1300 − 1000 = 300, available 0 | funds rule uses `balance − held` | `EdgeCaseLogicTest.canSpendExactlyTheAvailableAmountButNotOneCentMoreWhileOtherMoneyIsHeld` |
| B tries to withdraw 1 from a fully held 500 | 422; after 10 min, withdrawing 500 works | withdrawals use the same funds check | `EdgeCaseLogicTest.heldMoneyCannotBeWithdrawnEither` |
| Reverse A→B after B passed it on to C | rejected until B→C is reversed, then succeeds | reversals unwind in reverse order | `EdgeCaseLogicTest.reversalChainUnwindsInTheRightOrder` |
| Deposit 700, spend 600, reverse the deposit | 422; balance stays 100 | needs 700 available, has 100 | `EdgeCaseLogicTest.reversingADepositAfterTheMoneyWasSpentIsRejected` |
| Reversal and B's spend race after the hold (15 rounds) | exactly one succeeds; the 500 ends up in one place | both lock B's row | `EdgeCaseLogicTest.racingReversalAndSpendNeverBothSucceed` |
| 25.00 and 27.00 USD at 1.345 | 3362 (33.62) and 3632 (36.32) SGD | half-even: ties go to the even cent | `EdgeCaseLogicTest.fxRoundingTiesGoToTheEvenCent` |
| 1 JPY → USD; then a USD↔SGD round trip | 1 cent; both FX accounts end where they started | 0.665557 rounds to 1; 13450 × 0.7435 = 10000.075 rounds to 10000 | `EdgeCaseLogicTest.oneYenBecomesOneCentAndARoundTripBalancesTheFxAccounts` |
| Same key from another source account; same body with a new key | `IDEMPOTENCY_KEY_REUSED`; the new key executes (B = 200) | the key identifies the request | `EdgeCaseLogicTest.theKeyDefinesTheRequestNotTheBody` |
| Balance at `Long.MAX_VALUE − 10`, deposit 100 | `AMOUNT_OUT_OF_RANGE`, balance unchanged | `addExact` throws, everything rolls back | `EdgeCaseLogicTest.aPostingThatWouldOverflowABalanceIsRejectedCleanly` |
| Account opened mid-month; account never used | July opening 0; the quiet account gets a zero statement | an account absent from the previous close opens at 0 | `EdgeCaseLogicTest.accountsOpenedMidMonthStartAtZeroAndQuietAccountsStillGetAStatement` |
| Transfer of 1001 from 1000 | 422; no transaction row added | rollback | `TransferTest.insufficientFundsIsRejectedAndNothingChanges` |
| Spend the entire balance | balance 0, allowed | rule is `≥ 0`, not `> 0` | `TransferTest.canSpendExactlyTheFullBalance` |
| Same account, amount 0 or −5, wrong currency, unknown or system account | `SAME_ACCOUNT`, `INVALID_AMOUNT`, `CURRENCY_MISMATCH`, `NOT_FOUND`, `SYSTEM_ACCOUNT` | validated before locking | `TransferTest.rejectsInvalidTransfers` |
| Spend at 9:59 vs at exactly 10:00 after a credit | 9:59 rejected; 10:00 allowed | `available_at > now` means held | `TransferTest.incomingTransferIsHeldUntilTheHoldPeriodEnds` |
| Sender sends 400 of 1000 | sender's available is 600 at once | debits are never held | `TransferTest.senderSeesDebitImmediately` |
| Five transfers with identical timestamps | history in insertion order | ties broken by `entries.id` | `TransferTest.historyIsStableForEqualTimestamps` |
| `UPDATE entries` / `DELETE transactions` | error "append-only" | triggers | `TransferTest.journalIsAppendOnly` |
| B has 300 + 200 held; reverse the 300 | B: balance 200, held 200, available 0 | only the reversed credit is excluded | `ReversalTest.reversalOnlyClawsBackTheReversedCreditNotOtherHeldMoney` |
| Reverse the same transaction twice | `409 ALREADY_REVERSED` | checked under lock; `UNIQUE(reverses_id)` | `ReversalTest.cannotReverseTwice` |
| Reverse a reversal | `CANNOT_REVERSE_REVERSAL` | keeps the chain one level deep | `ReversalTest.cannotReverseAReversal` |
| Reverse a withdrawal of 400 | balance 1000 and spendable at once | reversal credits have no hold | `ReversalTest.withdrawalsCanBeReversedAndMoneyIsAvailableAtOnce` |
| FX reversal after recipient spent 13000 of 13450 SGD | 422; FX_USD unchanged | needs 13450 available, has 450 | `ReversalTest.fxReversalAfterRecipientSpentIsRejectedAndChangesNothing` |
| 10 concurrent reversals of one transaction | 1 succeeds, 9 get `ALREADY_REVERSED` | same locks, check after locking | `ReversalTest.concurrentReversalsOfTheSameTransactionApplyOnce` |
| Rate changes from 0.92 to 0.5, then reverse | USD back to exactly 50000 | original legs negated, no new rate | `FxTransferTest.reversingAnFxTransferReturnsTheExactOriginalAmountEvenIfTheRateChanged` |
| EUR → JPY with no rate row | `FX_RATE_UNAVAILABLE` | no triangulation | `FxTransferTest.missingRateIsRejected` |
| 1 EUR cent at rate 0.001 | `AMOUNT_TOO_SMALL` | converts to 0 | `FxTransferTest.amountThatConvertsToZeroIsRejected` |
| 100.00 USD → JPY | 15025 JPY | target has 0 decimals | `FxTransferTest.convertsIntoZeroDecimalCurrency` |
| 4×10¹⁸ × 3.0 | `ArithmeticException` | `BigInteger` product, `longValueExact` | `FxMathTest.doesNotOverflowOnLargeAmounts` |
| Failed transfer, then retry with the same key | retry executes, `replayed = false` | key rolled back with the failure | `IdempotencyTest.failedRequestDoesNotBurnTheKey` |
| Key reused for a deposit | `IDEMPOTENCY_KEY_REUSED` | operation name is part of the hash | `IdempotencyTest.sameKeyWithDifferentRequestIsRejected` |
| 20 threads with the same key | 1 transaction | primary key referees the race | `IdempotencyTest.concurrentDuplicatesProduceExactlyOneTransaction` |
| Replay after the original was reversed | original returned; money not re-sent | key points at the original | `IdempotencyTest.replayAfterReversalStillReturnsTheOriginalAndPostsNothing` |
| `"usd"` with `"  "` vs `"USD"` with null | treated as the same request | normalised before hashing | `IdempotencyTest.blankAndMissingDescriptionAreTheSameRequestAndCurrencyIsCaseInsensitive` |
| 100 concurrent transfers of 10 from 500 | exactly 50 succeed, balance 0 | row lock serialises them | `ConcurrencyTest.concurrentTransfersFromOneAccountNeverOverdraw` |
| 200 opposing A↔B transfers | no deadlock; both end at 10000 | sorted locks | `ConcurrencyTest.opposingTransfersDoNotDeadlock` |
| Transfer waited 5 min for a lock | stamped at arrival + 5 min | clock read after locking | `PostingTimeTest.aTransferThatWaitedForALockIsStampedWhenItIsApplied` |
| Entry at exactly 00:00:00Z on Apr 1 | belongs to April | `[start, end)` | `ReconciliationTest.entriesAreBucketedByUtcMonthWithMidnightBelongingToTheNewMonth` |
| Reconcile May at 00:04:59 vs 00:05:00 | rejected vs allowed | 5-minute grace | `ReconciliationTest.periodCanOnlyBeReconciledAfterItEndsPlusGrace` |
| Deposit to A commits after a REPEATABLE READ reconciliation took its snapshot; it then inserts A's statement (raw JDBC) | insert succeeds | `FOR NO KEY UPDATE` does not conflict with the FK check's `FOR KEY SHARE` | `ReconciliationConcurrencyTest.statementInsertSurvivesATransferCommittedAfterTheSnapshot` |
| Reconcile March 20 times while deposits keep committing to a reconciled account | every run completes; ledger healthy | same lock mode | `ReconciliationConcurrencyTest.reconciliationCompletesUnderLiveTraffic` |
| Cache bumped by +500 | `BALANCE_MISMATCH` (1000 vs 1500) and `TOTAL_MISMATCH` | cache recomputed from entries | `IntegrityCheckTest.detectsCachedBalanceDrift` |
| Hold set to `PT0S`, recipient spends at once | reversal rejected; A cannot recover the money | shows what the hold protects | `ZeroHoldTest.withoutAHoldMoneyIsSpendableAtOnceAndAReversalCanBeTooLate` |
| JSON amount `12.5` or `"100"` | 400, balance unchanged | Jackson strictness | `ApiTest.rejectsFractionalAndNonNumericAmounts` |
