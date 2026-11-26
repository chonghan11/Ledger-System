# Internal Ledger System

A double-entry financial ledger built with **Java 21, Spring Boot 3.5 and PostgreSQL**. It records transfers atomically, keeps balances exact under concurrency, and can prove its own correctness at any time.

All six "system behaviours" from the brief are implemented and tested, plus one addition: a **hold period** on incoming transfers so that reversals are safe.

| Behaviour | Status | Where |
|---|---|---|
| Record transactions / query balance & history | ✅ | `TransferService`, `AccountService` |
| Atomic transfer, reject on insufficient funds | ✅ | `PostingEngine` |
| Reversals (posted as correcting transactions) | ✅ | `ReversalService` |
| Idempotent retries (no double transfers) | ✅ | `IdempotencyService` |
| Ledger-wide integrity check | ✅ | `IntegrityCheckService` |
| Multi-currency transfers via exchange rates | ✅ | `FxMath`, `FxRateService` |
| Safe concurrent transfers to the same account | ✅ | `PostingEngine` (ordered row locks) |
| Month-end reconciliation | ✅ | `ReconciliationService` (+ monthly scheduler) |
| *Extra:* 10-minute hold on incoming transfers | ✅ | `PostingEngine`, `AccountRepository.heldCredits` |

---

## Contents
1. [Quick start](#1-quick-start)
2. [API](#2-api)
3. [Decisions made](#3-decisions-made)
4. [Core design: double-entry](#4-core-design-double-entry)
5. [The algorithms, behaviour by behaviour](#5-the-algorithms-behaviour-by-behaviour)
6. [Assumptions](#6-assumptions)
7. [Trade-offs and what I'd do next](#7-trade-offs-and-what-id-do-next)
8. [Testing](#8-testing)
9. [Project layout](#9-project-layout)

---

## 1. Quick start

**Prerequisites:** JDK 21 (`java` on the PATH or `JAVA_HOME` set) and Docker, **running**. Maven is *not* required: the repo ships the Maven wrapper, which downloads the right Maven version on first use. Ports 5432 and 8080 must be free.

**macOS / Linux / Git Bash:**
```bash
docker compose up -d          # PostgreSQL 17 on localhost:5432 (db/user/password: ledger)
./mvnw spring-boot:run        # builds and starts the app -> http://localhost:8080
./mvnw test                   # 78 tests; Testcontainers starts its own throwaway Postgres
```

**Windows PowerShell** (the default terminal in VS Code):
```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
```

Tips:
- **First run is slower:** the wrapper downloads Maven and the project's libraries (a few minutes, once).
- **`spring-boot:run` keeps the terminal busy** while the app runs (stop it with Ctrl+C). Run the tests in a **second terminal**, or before starting the app.
- **The tests don't need the `docker compose` database**: they start their own throwaway Postgres. Docker must still be running.

Flyway creates the schema on startup. That includes the system accounts (one `EXTERNAL` and one `FX` per currency) and a seed set of exchange rates.

**Web UI:** open **http://localhost:8080/**. It's a deliberately thin page with plain HTML/JS, no framework or build step, served by the same Spring Boot app. All rules live in the backend; the page only turns what you type into integer minor units, by string parsing and never `parseFloat`, and shows the API's responses and error codes. What it covers:
- **Accounts:** a list showing balance / available / on-hold, with system accounts behind a toggle. Create accounts; deposit, withdraw and transfer.
- **Transfers:** a cross-currency preview using the same integer half-even maths as the server. A **"Send same request again"** button resends the last request with the same `Idempotency-Key`, so you can watch the server replay it without moving money.
- **History:** running balance and hold countdowns, plus a **Reverse** button on your own *outgoing* transfers (only the sender can take money back; see §5.6). Click a row to see its double-entry legs and their per-currency net of 0.
- **Operations:** integrity check (also shown as the header badge), month-end reconciliation and exchange rates.

Balances refresh every 10 seconds, so you can watch holds expire.

**Try it** (amounts are integer *minor units*: `10000` = 100.00 USD):

```bash
# create two accounts
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"Alice","currency":"USD"}'
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"name":"Bob","currency":"SGD"}'

# fund Alice (money enters the ledger from the EXTERNAL account)
curl -s -X POST localhost:8080/accounts/$ALICE/deposits -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: dep-001' -d '{"amount":10000,"currency":"USD"}'

# transfer 25.00 USD from Alice to Bob (converted to SGD at the configured rate)
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: tx-001' \
     -d '{"fromAccountId":"'$ALICE'","toAccountId":"'$BOB'","amount":2500,"currency":"USD"}'

curl -s localhost:8080/accounts/$BOB                 # balance, availableBalance, heldBalance
curl -s localhost:8080/accounts/$ALICE/transactions  # history with running balance
curl -s localhost:8080/admin/integrity-check         # "healthy": true
```

> **Windows note.** If your Windows user folder contains a space (e.g. `C:\Users\Jane Doe`), tests can fail with `Unable to establish loopback connection`. The JDK's NIO selector creates an AF_UNIX socket under `%TEMP%`, and the 8.3 short-name form of that path (`C:\Users\JANEDO~1\...`) is rejected inside the Spring context. Point `TEMP`/`TMP` at a plain folder for the test run:
> `$env:TEMP="C:\tmp"; $env:TMP="C:\tmp"; .\mvnw.cmd test`.
> The same applies to `mvnw.cmd` itself. I patched the wrapper so it quotes the Maven path (upstream 3.3.2 splits it at the space).

---

## 2. API

All errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`, with a stable machine-readable `code`:

```json
{ "status": 422, "title": "Unprocessable Entity", "code": "INSUFFICIENT_FUNDS",
  "detail": "account 7f1c… has available balance 400 but 500 is required" }
```

| Method & path | Purpose | Notes |
|---|---|---|
| `GET /accounts` | List accounts | customers first, then system accounts; includes available/held |
| `POST /accounts` | Create a customer account | `{"name","currency"}` → 201 |
| `GET /accounts/{id}` | Balance | returns `balance`, `availableBalance`, `heldBalance` |
| `GET /accounts/{id}/transactions` | History | ordered by timestamp, then entry id; includes `runningBalance` and counterparty |
| `POST /accounts/{id}/deposits` | Money in | `Idempotency-Key` required |
| `POST /accounts/{id}/withdrawals` | Money out | `Idempotency-Key` required; checks available funds |
| `POST /transactions` | **Transfer** | `{"fromAccountId","toAccountId","amount","currency","description?"}`, `Idempotency-Key` required → 201 with transaction id and entries |
| `GET /transactions/{id}` | Transaction + its entries | `status` is `POSTED` or `REVERSED` |
| `POST /transactions/{id}/reversal` | Reverse | `{"reason?"}` → 201 with the new reversal transaction |
| `GET /fx-rates`, `GET /fx-rates/{base}/{quote}`, `PUT /fx-rates/{base}/{quote}` | Exchange rates | `{"rate":"1.345"}`; parsed exactly, max 8 decimals |
| `GET /currencies` | Supported currencies | code + decimal places (USD 2, JPY 0) |
| `GET /admin/integrity-check` | Verify all ledger invariants | report with per-currency totals + discrepancies |
| `POST /admin/reconciliations` | Month-end reconciliation | `{"period":"2026-10"}`; month must have ended |
| `GET /admin/reconciliations/{id}` | Stored reconciliation run | statements + discrepancies |

**Error codes:** `400` VALIDATION_FAILED, MALFORMED_REQUEST, MISSING_HEADER, INVALID_AMOUNT, INVALID_IDEMPOTENCY_KEY, INVALID_RATE · `404` NOT_FOUND · `405`/`415` from Spring MVC, same format · `409` ALREADY_REVERSED, CONSTRAINT_VIOLATION, CONCURRENCY_CONFLICT, RECONCILIATION_IN_PROGRESS, REQUEST_IN_PROGRESS (defensive; can't normally occur) · `422` INSUFFICIENT_FUNDS, CURRENCY_MISMATCH, SAME_ACCOUNT, SYSTEM_ACCOUNT, IDEMPOTENCY_KEY_REUSED, FX_RATE_UNAVAILABLE, AMOUNT_TOO_SMALL, AMOUNT_OUT_OF_RANGE, CANNOT_REVERSE_REVERSAL, UNSUPPORTED_CURRENCY, PERIOD_NOT_CLOSED · `500` INTERNAL_ERROR (the transaction is rolled back). Every error, including unknown routes, uses the same problem+json shape.

---

## 3. Decisions made

The main choices in one place. Each is explained in more detail in the section it points to.

| Area | Decision | Why | Alternative considered |
|---|---|---|---|
| **Language** | Java 21 + Spring Boot 3.5 | The brief prefers Java; `@Transactional` and mature JDBC suit a ledger | Python (FastAPI): less code, but transactions are managed by hand |
| **Database** | PostgreSQL 17 | ACID transactions, row-level locks, `CHECK`/`UNIQUE` constraints and triggers | In-memory / H2: locks behave differently, so concurrency tests would prove little |
| **Data access** | Plain SQL with `JdbcClient` | The locking query and the aggregates *are* the logic, so they stay visible; no ORM caching | JPA / Hibernate (§4) |
| **Money** | `long` integer minor units; JSON floats rejected | Rounding errors are impossible; overflow throws instead of wrapping | `double` (inexact); `BigDecimal` (exact, but slower and easy to misuse) |
| **Bookkeeping** | Double-entry: each transaction's entries sum to 0 per currency, with `EXTERNAL` and `FX` system accounts | Money can't be created or destroyed, and the books can be verified at any time | Updating two balances directly: nothing to check them against |
| **Balances** | Entries are the source of truth; `accounts.balance` is a cache updated in the same transaction | O(1) reads and funds checks, and the cache is verifiable | Summing all entries on every read |
| **History** | Append-only (enforced by DB triggers); corrections are new transactions | A complete audit trail | Editing or deleting rows |
| **Concurrency** | Pessimistic row locks (`FOR NO KEY UPDATE`), always taken in ascending account-id order | Busy accounts queue instead of failing; the fixed order prevents deadlocks; this lock mode doesn't block month-end checks | Optimistic versioning + retry: retry storms on busy accounts (§5.2) |
| **Posting time** | Server clock (UTC), read *after* the locks are held | History and month-end buckets follow the order postings were really applied | Reading the clock on arrival, a bug found in review (§5.1) |
| **Idempotency** | Required client `Idempotency-Key`, stored as a primary key in the same DB transaction as the money; only successes are remembered | Retries never pay twice; the database referees races; a failed request can be retried | A separate cache such as Redis (not atomic with the money); remembering failures |
| **Key reuse** | Same key + different request → `422` | Follows the IETF Idempotency-Key draft | Silently replaying or executing it |
| **Reversals** | A new negating transaction, at most once (`UNIQUE`); a reversal can't itself be reversed | History is never edited; a double reversal is impossible | Editing or deleting the original |
| **Spent money** | A reversal is rejected if the recipient no longer has the money | Customers never go negative | Allowing a negative balance as a receivable (§7) |
| **Hold period** | Incoming transfers held 10 minutes (configurable), calculated when read, with no release job | A reversal during the hold always succeeds; no background job can fail or double-release | No hold; a scheduled release job (§5.7) |
| **What isn't held** | Deposits and reversal credits are available immediately | Deposits arrive already cleared; returned money was the sender's own | Holding everything |
| **Who may reverse** | The sender (the UI shows Reverse only on outgoing transfers); deposits and withdrawals by operators via the API | Matches a bank recall; a recipient returns money with a normal transfer | Letting either side reverse (§5.6) |
| **FX booking** | Four legs through per-currency FX clearing accounts | Each currency still nets to 0, and the bank's currency position is visible | Two legs (−USD, +SGD): breaks the per-currency balance (§5.5) |
| **FX maths** | Rate × 10⁸ stored as an integer, `BigInteger` maths, one half-even rounding step | Exact, and unbiased over many conversions | Floating point; half-up rounding, which drifts upward |
| **FX rates** | Directional pairs, no triangulation; reversals negate the original legs | Matches how dealers quote; the sender gets back exactly what they sent | Inverting rates; re-converting at today's rate |
| **Amounts** | Stated in the source account's currency (which must be given); 0 < amount ≤ 10¹⁵ | Prevents confusion over which side the amount refers to; keeps every sum far from overflow | An implicit currency; unbounded amounts |
| **Integrity check** | Re-derives every invariant from the entries in one REPEATABLE READ snapshot | A single coherent picture even under live traffic | Trusting the cached balances (§5.4) |
| **Month-end** | The first run of a month is its official close (unique index); re-runs and the next month are compared against it; 5-minute grace period | Re-running can't launder back-dated changes; postings in flight at midnight are included | Using the latest run as the reference, a bug found in review (§5.8) |
| **Errors** | RFC 9457 problem+json with a stable `code` | Clients branch on codes, never on messages | Ad-hoc error bodies |
| **Testing** | Testcontainers on real PostgreSQL; concurrency tests released together by a latch | Proves the locking really works | Mocks or H2 (§8) |
| **UI** | One plain HTML/JS page served by Spring Boot; all rules stay in the backend; SGD pre-selected | No build step, nothing to drift out of sync with the API | A separate single-page-app framework |
| **Delivery** | GitHub Actions (tests, static checks, smoke test, image); Docker on Ubuntu-based Temurin, pinned and non-root | Conventional, reproducible defaults | Alpine: smaller, but swaps glibc for musl |

---

## 4. Core design: double-entry

Everything else builds on this.

### Money is an integer
Every amount is a `long` count of the currency's **minor unit** (cents for USD, yen for JPY), stored as `BIGINT`. There is no `float`, `double` or decimal arithmetic on money anywhere:
- **JSON:** Jackson is configured to *reject* `12.5` and `"100"`. By default it would silently truncate `12.5` to `12`.
- **Arithmetic:** uses `Math.addExact`/`subtractExact`, which throw on overflow instead of silently wrapping round to a negative number.
- **Decimal places per currency** live in a `currencies` table (USD 2, JPY 0, …).

### Three core tables

```
accounts      id, name, currency, type (CUSTOMER|SYSTEM), system_role (EXTERNAL|FX), balance, created_at
transactions  id, type (DEPOSIT|WITHDRAWAL|TRANSFER|REVERSAL), source/destination account, source/destination
              amount + currency, fx_rate_scaled, reverses_id (UNIQUE), description, created_at
entries       id (BIGSERIAL), transaction_id, account_id, amount (signed), currency, available_at, created_at
```

- A **transaction** is the business event. Its **entries** are the actual postings.
- The sign convention is deliberately simple: a positive amount increases the account's balance, a negative one decreases it.
- **Invariant:** for every transaction, the entries of each currency sum to **zero**. Money is never created or destroyed, only moved.
- **Money from outside** enters through a per-currency `EXTERNAL` system account, which goes negative by exactly what customers deposited. So even deposits balance, and "total money in the ledger" becomes something you can check (see §5.4).

```
Deposit 100 USD to Alice          Transfer 30 USD Alice -> Bob
  EXTERNAL_USD   -100               Alice   -30
  Alice          +100               Bob     +30   (available_at = now + 10 min)
```

### Source of truth vs. cache
`entries` is the **source of truth**. `accounts.balance` is a **cache** of `SUM(entries.amount)`, updated in the same database transaction as the entries. The cache exists so that balance reads and the insufficient-funds check are O(1) instead of summing the account's whole history. Because it's a cache, the integrity check (§5.4) verifies it against the entries.

### History is append-only
Database triggers reject `UPDATE`/`DELETE` on `entries` and `transactions`. Mistakes are fixed by posting new transactions (reversals), never by editing history, so the audit trail is complete by construction.

### Constraints as a second line of defence
The application validates everything, and the schema enforces the critical rules again:
- `CHECK (type <> 'CUSTOMER' OR balance >= 0)`: a customer can never go negative, even if application code were wrong.
- `UNIQUE (reverses_id)`: a transaction can be reversed at most once.
- `CHECK (source_account_id <> destination_account_id)`, `CHECK (amount <> 0)`, and FX-rate presence matching whether the transfer is cross-currency.
- `PRIMARY KEY (idempotency_key)`: the referee for duplicate requests.

### Why plain SQL (`JdbcClient`) instead of JPA
In a ledger, the locking query and every aggregate are the logic, so they should be visible in the code. JPA's persistence context can return a cached, stale entity even after a `FOR UPDATE` query. It also delays writes until a flush, which makes the order of statements inside a transaction harder to reason about. With `JdbcClient`, each line of SQL runs exactly where it's written.

---

## 5. The algorithms, behaviour by behaviour

### 5.1 Atomic transfer (`PostingEngine`)

Every money movement (deposit, withdrawal, transfer, FX transfer, reversal) is expressed as a list of **legs** (`account, signed amount, available_at`) and goes through one method. It all happens in **one database transaction**:

```
BEGIN
 1. claim the idempotency key                                   (§5.3)
 2. read immutable account facts (currency, type)               -> decide which accounts are involved
 3. LOCK all involved accounts: SELECT … FOR NO KEY UPDATE, in ascending id order   (§5.2)
 4. read the clock: this is the posting time                     (only now, with every lock held)
 5. check legs: every currency nets to zero                      (else a programming error)
 6. funds check: for each customer account being debited,
        newBalance - heldCredits >= 0                            (else 422 INSUFFICIENT_FUNDS)
 7. INSERT transaction header, INSERT entries (created_at = posting time, available_at = + hold)
 8. UPDATE cached balances (Math.addExact: never wraps)
 9. link idempotency key -> transaction id
COMMIT   (any exception at any step -> ROLLBACK: nothing happened)
```

**Why the clock is read at step 4, not when the request arrives.** A request may wait at step 1 or step 3 behind other transactions. Suppose withdrawal W arrives first but waits for the lock while deposit D (arriving later) commits, and W only passes the funds check because of D's money. If W kept its arrival time, its entry would be timestamped *before* D. History would then show a negative running balance that never existed, and if midnight fell in between, the month-end reconciliation would raise a false alarm. Reading the clock once every lock is held means postings to the same account are timestamped in exactly the order they were applied. The hold period also starts from the posting time, so a lock wait can't shorten it. `PostingTimeTest` proves this deterministically: it holds a row lock from a raw JDBC connection, lets a transfer queue behind it, moves the clock forward 5 minutes, releases the lock, and asserts the transfer carries the later time.

Atomicity comes from the database: Spring's `@Transactional` opens one Postgres transaction for the whole method, and any `RuntimeException` rolls back every write. The engine's methods use `Propagation.MANDATORY`, so calling them outside a transaction fails immediately instead of silently auto-committing step by step.

### 5.2 Concurrency: no lost updates, no deadlocks

**The danger.** Two transfers out of the same account, each with 100 available and each sending 80: both read 100, both pass the check, both write. One update is lost, or the account ends at −60.

**The fix: pessimistic row locks.** Step 3 locks every account row involved. A second transaction that touches the same account **blocks** at that line until the first commits. Postgres then hands it the *latest committed* row, so its funds check sees the true balance. Read, check and write can't interleave.

**Why `FOR NO KEY UPDATE` and not `FOR UPDATE`.** Both lock modes block other postings to the same account. The difference is how they interact with foreign-key checks. Inserting any row that references an account (a month-end statement, for example) makes Postgres take a light `FOR KEY SHARE` lock on that account. `FOR UPDATE` conflicts with it; `FOR NO KEY UPDATE` doesn't, because it promises not to change the row's key, and postings only ever change `balance`. The first version used `FOR UPDATE`. A logic audit predicted, and `ReconciliationConcurrencyTest` then proved, that any transfer committing during a month-end run made the reconciliation fail with `could not serialize access due to concurrent update`. On a busy system the scheduled close would never complete. The lighter lock fixes that with no loss of safety.

**Deadlock avoidance: global lock order.** If T1 locks A then B while T2 locks B then A, each holds one lock while waiting for the other. Postgres detects that deadlock after about a second and aborts one of them, so a perfectly valid transfer fails. To prevent it, all locks are taken in **ascending account id order**, whatever the direction of the transfer. Every transaction climbs the same ladder, so no cycle can form. This covers multi-account postings too: an FX transfer locks four accounts, sorted together.

**Why pessimistic rather than optimistic (version column + retry)?** Hot accounts (a merchant, a payroll account) are exactly where contention happens. Optimistic locking would turn contention into retry storms. Pessimistic locks just queue the work, and the critical section is a handful of short statements.

**Proven by tests** (`ConcurrencyTest`). Each fires real parallel transactions released by a latch:
- 100 simultaneous transfers of 10 from an account holding 500 → exactly 50 succeed, 50 are rejected, and the balance is exactly 0.
- 200 simultaneous credits into one hot account → no lost updates.
- 200 opposing transfers (A→B and B→A at once) → no deadlocks, money conserved.
- 60 opposing cross-currency transfers → every currency still balances.

### 5.3 Idempotency: repeated requests don't repeat transfers

The client sends an `Idempotency-Key` header, required on every money-moving `POST`. Inside the **same** database transaction as the transfer:

```sql
INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
VALUES (:key, sha256(operation + canonical request fields), now())
ON CONFLICT (idempotency_key) DO NOTHING
```

- **1 row inserted:** this request owns the key, so it executes. It then writes the resulting transaction id onto the key row, and both commit together.
- **0 rows inserted:** the key exists. If the hashes match, return the original transaction (HTTP 201, header `Idempotent-Replayed: true`) without posting anything new. If the hashes differ, the client reused a key for a different request, which is almost certainly a bug, so it's refused with `422 IDEMPOTENCY_KEY_REUSED`. That follows the IETF *Idempotency-Key* draft.

**Why this is race-safe.** If two identical requests arrive at the same moment, the second `INSERT` sees the first's *uncommitted* key and Postgres makes it **wait** until the first transaction finishes. The first commits, so the second gets "conflict" and replays it. Or the first rolls back, so the second's insert succeeds and it executes. The primary key referees the race; there's no application-level lock. The key is claimed **before** any account lock, so duplicates queue on the key and never contend for account rows. Test: 20 threads send the same key at once → exactly one transaction is posted.

**Failures don't burn the key.** If the transfer fails (say, insufficient funds), the rollback also removes the key row. The client can fix the problem and retry with the *same* key, and nothing was moved the first time. Only successes are remembered. That's safe because a failed request has no effect to protect.

**The hash is canonical.** It covers the operation plus the meaningful fields, not the raw bytes, so whitespace or JSON field order don't matter. One key reused across endpoints (a deposit key replayed as a transfer) is also rejected.

**Reversals** need no key: they're naturally idempotent, since `UNIQUE(reverses_id)` means a second attempt returns `409 ALREADY_REVERSED` with the existing reversal's id.

### 5.4 Integrity check: does the money add up?

`GET /admin/integrity-check` re-derives everything from first principles and lists every violation:

| Check | Rule |
|---|---|
| `LEDGER_IMBALANCE` | per currency, `SUM(all entries) = 0` |
| `TOTAL_MISMATCH` | per currency, customer balances + FX positions = −EXTERNAL balance = **net money deposited** (the "expected sum") |
| `BALANCE_MISMATCH` | each account's cached balance = `SUM(its entries)` |
| `UNBALANCED_TRANSACTION` | each transaction's entries sum to 0 per currency |
| `INCOMPLETE_TRANSACTION` | each transaction has ≥ 2 entries |
| `CURRENCY_MISMATCH` | each entry is in its account's currency |

**Consistent snapshot.** The check runs in a single **REPEATABLE READ** transaction, so all its queries see the ledger at the same instant while transfers keep committing. To be precise: each check is one SQL statement, and Postgres already reads a single statement from one snapshot, so no individual check can be fooled by an in-flight transfer. REPEATABLE READ adds consistency *across* the statements, so the totals, per-account results and counts in the report all describe the same moment. Tests corrupt the database directly with raw SQL (drifting a cached balance, inserting a one-legged transaction) and assert each is detected.

### 5.5 Multi-currency transfers

`amount` is always in the **source** account's currency, and `currency` must match it. This guards against a client mixing up which side the amount refers to.

**Integer-only conversion.** Rates are stored as `rate × 10⁸` in major units (1 USD = 1.345 SGD → `134500000`). Converting `a` minor units between currencies with `s` and `d` decimal places:

```
result = a × rateScaled × 10^d  /  (10^8 × 10^s)      -- BigInteger, so the product can't overflow
```

There's exactly **one rounding step** in the whole system: that division, rounded **HALF_EVEN** (banker's rounding). Rounding half-up on every conversion would bias results upwards over millions of transfers; half-even doesn't drift. If the result is 0 (a tiny amount at a small rate), the request is rejected (`AMOUNT_TOO_SMALL`) rather than moving money for nothing.

**Keeping each currency balanced.** A USD→SGD transfer can't simply be `−100 USD / +134.50 SGD`: that would break "each currency nets to zero". It goes through per-currency **FX clearing accounts**:

```
Alice     -10000 USD        FX_SGD   -13450 SGD
FX_USD    +10000 USD        Bob      +13450 SGD   (held 10 min)
```

The FX accounts hold the ledger's currency position: it now has 100 USD more and 134.50 SGD less. In a real bank, treasury would square this position with a counterparty, and that position is where any rounding remainder shows up. The applied rate is stored on the transaction for audit.

**Rates are directional** (USD→SGD and SGD→USD are separate rows, as real desks quote them with a spread). There's **no triangulation**: a missing pair is rejected (`FX_RATE_UNAVAILABLE`) rather than guessed through a third currency.

### 5.6 Reversals

`POST /transactions/{id}/reversal` posts a **new** `REVERSAL` transaction whose entries are the exact negation of the original's, with `reverses_id` pointing back. The original is never modified; its view shows `status: REVERSED` and `reversedByTransactionId`.

- **At most once.** The "already reversed?" check runs *after* the accounts are locked. A concurrent reversal of the same transaction locks the same accounts, so by the time we hold the locks it has either committed (and we see it) or it's waiting behind us. `UNIQUE(reverses_id)` backs this up. Test: 10 concurrent reversals → exactly 1 succeeds.
- **FX transfers reverse at the original rate.** Each original leg is negated, so the sender gets back exactly what they sent even if the rate has moved since, and each FX clearing account returns to exactly where it was before the transfer. The reversal's header therefore carries **no rate of its own** (`fx_rate_scaled` is NULL). Copying the original USD→SGD rate onto an SGD→USD header would misstate it, so the rate stays on the original transaction that the reversal points to.
- **A reversal can't be reversed**: post a new transfer instead. That keeps the "reversed by" chain one level deep and unambiguous.
- Deposits and withdrawals can be reversed too (e.g. a chargeback, or an ATM that failed to pay out).

**Who may reverse.** A reversal is the *sender* taking money back, like a bank recall, so the UI offers **Reverse** only on a customer's own outgoing transfers. A recipient who wants to return money sends an ordinary transfer, which is a new payment rather than a correction. Reversing deposits and withdrawals is a back-office correction: the API supports it (and it's tested), but customers aren't offered it. The API itself has no authentication in this take-home, so `POST /transactions/{id}/reversal` acts as an operator endpoint. In production, the authorisation layer would enforce the same rule: a customer may reverse only transactions where their account is the source, and only operators may reverse deposits, withdrawals or anything else (see §7, *Admin surface*).

### 5.7 The hold period (the addition)

The open question in a reversal is: *what if the recipient has already spent the money?* Rather than allowing negative balances, incoming transfers are **held for 10 minutes** (configurable via `ledger.hold-period`; `PT0S` turns holds off).

- Each credit entry carries `available_at = created_at + hold`.
- `balance` = everything posted. `heldBalance` = credits whose `available_at` is still in the future, **excluding credits from transactions that have since been reversed**. `availableBalance = balance − heldBalance` is what can be spent.
- The funds check is a single rule: *after posting, `balance − held ≥ 0` for every debited customer account*.
  - For a normal transfer of `X`, that's just `available ≥ X`.
  - For a **reversal during the hold**, the clawed-back credit stops counting as held at the same moment it's debited: `(B − X) − (H − X) = B − H ≥ 0`. So it **always succeeds**, because that money was never spendable.
  - For a **reversal after the hold**, the recipient needs `X` available, otherwise `422 INSUFFICIENT_FUNDS`. The ledger never forces a customer below zero.

**Design choices:**
- **Held funds are computed when they're read, not released by a job.** There's no background process moving money from "held" to "available", so no job can crash, fall behind or double-release. Time passing *is* the release. The cost is one indexed query (`entries (account_id, available_at) WHERE amount > 0`), and it runs under the same row lock as the transfer, so it's race-free.
- **The sender is debited immediately.** Outgoing money is never "held" for the sender.
- **Deposits and money returned by a reversal are available immediately.** A deposit is assumed to arrive already cleared from the outside world. Reversal proceeds were the sender's own money to begin with. *(In our early design discussion I suggested holding reversal credits too. I dropped that, because holding someone's own returned money protects against nothing.)*
- **All time comes from an injectable `Clock`**, so tests move time forward ("11 minutes later") instantly.
- **The boundary is inclusive of release:** money is spendable at exactly `created_at + hold` (tested to the second).

### 5.8 Month-end reconciliation

`POST /admin/reconciliations {"period":"2026-10"}` (also runs automatically at 00:05 UTC on the 1st, for the previous month). For every account that existed by month end:

```
opening = SUM(entries before the month)
credits = SUM(positive entries in the month)       debits = SUM(negative entries in the month)
closing = opening + credits − debits
```

It then cross-checks:

| Discrepancy | Meaning |
|---|---|
| `OPENING_BALANCE_MISMATCH` | this month's recomputed opening ≠ the closing balance in last month's **official close**. History before this month changed *after* that month was closed. An account absent from that close must open at 0. |
| `PERIOD_CHANGED_AFTER_CLOSE` | when a month is **re-run**, its opening, credits, debits or entry count ≠ its own official close |
| `BALANCE_MISMATCH` | cached balance ≠ sum of entries |
| `NEGATIVE_CLOSING_BALANCE` | a customer account closed the month below zero |
| `UNBALANCED_TRANSACTION` | a transaction posted in the month doesn't net to zero (and every account it touched is marked) |

**Freezing statements is the key idea.** Each run stores a statement per account (opening, credits, debits, closing, entry count). The **first** run of a month is its *official close*, marked by an `official_close` flag. A partial unique index (`ON reconciliation_runs (period_start) WHERE official_close`) makes "exactly one official close per month" a database guarantee. If two first runs race, the second gets `409 RECONCILIATION_IN_PROGRESS` and can simply retry as a re-run. Next month's opening is compared with that frozen closing. This catches tampering that leaves the ledger *internally* consistent. The test back-dates a perfectly balanced transfer into an already-closed month by raw SQL, with cached balances adjusted to match. The integrity check stays green, but the next month's reconciliation flags both accounts.

**Why the first run is the reference, not the latest.** In my first version, the latest run was the reference. The code review caught the hole in that: after back-dating an entry, simply *re-running* the tampered month would produce a clean run, re-freeze the altered figures, and make the next month look fine. Now a re-run is compared against the official close (`PERIOD_CHANGED_AFTER_CLOSE`), and the next month always chains to the official close, so re-running can never launder a change.

Other details:
- **Grace period:** a month can only be reconciled 5 minutes after it ends (UTC); `PERIOD_NOT_CLOSED` otherwise. A posting stamped 23:59:59.9 may still be committing at midnight, and the grace period guarantees it's included. The scheduler runs at 00:05 for the same reason.
- **Snapshot:** like the integrity check, it runs in one REPEATABLE READ snapshot, so live traffic causes no false alarms. Thanks to the `FOR NO KEY UPDATE` lock (§5.2), live traffic can't make it fail either. `ReconciliationConcurrencyTest` runs it 20 times while deposits keep committing.
- **Bucketing:** months are bucketed by entry `created_at` in UTC. An entry at exactly `00:00:00Z` on the 1st belongs to the new month (tested).

---

## 6. Assumptions

- **Amounts in minor units**, `0 < amount ≤ 10¹⁵` (10 trillion USD). The cap is far above any real payment. It keeps every balance and aggregate well inside a 64-bit integer, so extreme input gets a clean 400 instead of an overflow. `amount` is in the **source** currency, and `currency` must match it. Currency codes are case-insensitive (`usd` = `USD`).
- **Blank text** (`description`, `reason`) is treated as absent, so `""` and a missing field are the same request for idempotency purposes.
- **No overdrafts:** customer accounts never go negative. System accounts (`EXTERNAL`, `FX`) may, by design.
- **Accounts are created empty** and funded by deposits. Customers can't transfer to or from system accounts directly (`SYSTEM_ACCOUNT`).
- **Supported currencies:** USD, SGD, EUR, GBP (2 decimals) and JPY (0 decimals). More can be added with one row in `currencies`, plus its system accounts.
- **Timestamps** come from the server clock (UTC, microsecond precision). Clients can't backdate. History is ordered by `(created_at, entry id)`, a strict total order even when timestamps are equal.
- **Months** are reconciled in UTC.
- **A transfer has one source and one destination.** The posting engine supports N legs (FX uses 4), so splits or fees would be a small addition.
- **No authentication.** `/admin/*` endpoints would sit behind admin auth, and account access would be scoped to its owner. Left out as out of scope.

---

## 7. Trade-offs and what I'd do next

| Topic | Current choice | At larger scale |
|---|---|---|
| **Hot system accounts** | Every deposit locks `EXTERNAL_<ccy>`, and every FX transfer locks the `FX_<ccy>` rows, so these serialize. Fine at this scale. | Shard each system account into N sub-accounts, picking one at random per posting. Or stop caching system balances (they're never funds-checked) and compute them from entries. |
| **Integrity / reconciliation cost** | Full scans of `entries`: simple and obviously correct. | Incremental checkpoints: verify only entries after the last verified `entries.id`, chaining from the stored totals. Run on a read replica. |
| **History endpoint** | Returns the full history. | Keyset pagination on `(created_at, id)`, which the existing index already supports. |
| **Idempotency key retention** | Kept forever. | Expire keys after e.g. 24–72 h with a cleanup job (Stripe-style), and scope keys per client/API token. |
| **FX rates** | Static table, admin-updated, no expiry. | Rates fed from a provider with a validity window. Transfers reject rates that are too old. Optionally quote-then-execute with a locked quote id. |
| **Hold period** | One global duration for transfers. | Risk-based holds (new accounts, large amounts), plus an explicit early-release endpoint. |
| **Reversal after spend** | Rejected (`422`). | Optionally allow authoritative corrections to push a customer negative, as a flagged receivable for collections (like a chargeback). The schema's non-negative `CHECK` would then be relaxed for that path only. |
| **Locking** | Pessimistic, one Postgres primary. | Fine to very high throughput on one primary. Beyond that, partition accounts across ledgers and route cross-partition transfers through a clearing account, with a saga/outbox. |
| **Timestamps** | App-server clock, read under lock. Correct per account on one server. | With several app servers, clock skew could reorder postings across servers. Use the database clock (`clock_timestamp()`) or order strictly by the entry sequence. |
| **Reconciliation coverage** | Chains to the previous month only, and the scheduler doesn't catch up on months missed while the app was down. Several instances would each run it. | Catch-up for un-reconciled months, a distributed lock or leader election for the job, and `BALANCE_MISMATCH` measured at period end rather than now. |
| **Reversal endpoint** | Naturally idempotent (`409 ALREADY_REVERSED`) but takes no `Idempotency-Key`, so a retry gets 409 instead of a replayed 201. | Accept an optional key for a uniform client experience. |
| **Admin surface** | `/admin/*`, `PUT /fx-rates` and reversals are open. | Role-based auth. Reversing a *withdrawal* re-credits money that has physically left, so it needs operator approval (four-eyes). |
| **FX positions** | Spread gains and losses accumulate in the FX clearing accounts; nothing revalues them. | A treasury process that squares positions and books P&L. |
| **Account lifecycle** | Accounts can't be frozen or closed. | A status column checked by the posting engine. |
| **Account list** | `GET /accounts` is unpaginated, and its held-amount aggregate filters on `available_at` across all accounts, which the `(account_id, available_at)` index can't range-scan. The UI polls it every 10 s while the tab is visible (no integrity check on the timer). | Paginate, and add an index on `(available_at) WHERE amount > 0`. |
| **Long audit snapshots** | The integrity check and reconciliation hold a REPEATABLE READ snapshot for their full scans. On a huge ledger that delays Postgres's cleanup (vacuum) while they run. Reconciliation also inserts statements one row at a time. | Run them on a read replica, checkpoint incrementally (see above), and batch the statement inserts. |
| **Container startup** | A fat jar (~25 MB) in one image layer, so every release re-uploads it. | Spring Boot's layered-jar extraction (`-Djarmode=tools extract --layers`) and Class Data Sharing for faster pushes and startup. |

---

## 8. Testing

```bash
./mvnw test
```

There are **78 tests**. Integration tests run against a **real PostgreSQL 17** started by Testcontainers. That matters: an in-memory database (H2) handles row locks differently, so concurrency tests passing on it would prove little.

| Test class | Covers |
|---|---|
| `FxMathTest` | Integer conversion, half-even rounding, different decimal places, overflow, exact rate parsing (pure unit test) |
| `TransferTest` | Atomic transfers, rejected transfers change nothing, validation, hold timing to the second, history order and running balance, append-only triggers |
| `ConcurrencyTest` | Overdraft attempts racing, hot-account credits, opposing transfers (deadlock), concurrent FX |
| `IdempotencyTest` | Replays, key reuse with a different body, failed requests not burning keys, 20-thread duplicate race |
| `ReversalTest` | During and after hold, spent-money rejection (also for FX), double reversal, reversal of reversal, deposit and withdrawal reversal, concurrent reversals, no rate on reversal headers |
| `FxTransferTest` | Conversion and 4-leg balancing, JPY, reversal at the original rate after a rate change, missing rate, amounts rounding to zero |
| `IntegrityCheckTest` | Clean ledger; detects drifted balances and one-legged transactions injected by raw SQL |
| `ReconciliationTest` | Month-to-month chaining, grace period, exact-midnight bucketing, balance drift, back-dated history after close, re-running a tampered month |
| `PostingTimeTest` | A transfer that waited for a lock is stamped when it's applied, not when it arrived |
| `ReconciliationConcurrencyTest` | Month-end reconciliation survives transfers committing mid-run: a deterministic raw-JDBC reproduction, plus 20 real runs under live deposits |
| `EdgeCaseLogicTest` | Exact-available spending with money on hold, held money can't be withdrawn, A→B→C reversal chain, deposit reversal after spend, a spend and a reversal racing (15 rounds, never both), FX half-even ties both ways, 1 yen → 1 cent, key-vs-body idempotency semantics, balance overflow, mid-month accounts in reconciliation |
| `ZeroHoldTest` | With the hold switched off, money is spendable at once and a reversal can come too late, which shows what the hold protects against |
| `ApiTest` | End-to-end over HTTP: status codes and error codes; `12.5`, `"100"`, over-cap and out-of-`long` amounts and unknown fields rejected; replay header; FX rate endpoints; problem+json for unknown routes |

Concurrency tests release all threads through a `CountDownLatch` to maximise contention, then check exact final balances. They also run the integrity check, so they fail on any lost update, overdraft or deadlock.

### CI/CD (`.github/workflows/ci.yml`)
Every push and pull request runs four jobs:

| Job | What it checks |
|---|---|
| **Build & test** | `./mvnw verify`: compiles the code and runs all 78 tests against real Postgres via Testcontainers on the GitHub runner. Uploads test reports and the jar. |
| **Static checks** | JavaScript syntax (`node --check`); the UI references assets that exist; shell scripts parse and use LF line endings; and a guard that fails if `float`/`double` ever appears in `src/main/java`, because money must stay integer. |
| **End-to-end smoke test** | Starts the *packaged jar* against Postgres from `docker compose`, then runs `scripts/smoke-test.sh`: 17 HTTP checks covering the UI being served, idempotent replay, key reuse, holds, FX conversion, rejections, reversal, history and the integrity check. |
| **Docker image** | Builds the image on every run, which proves the `Dockerfile` works. On `main` only, it pushes to GitHub Container Registry as `ghcr.io/<owner>/<repo>:latest` and `:<sha>`, using the built-in `GITHUB_TOKEN`; no secrets to configure. |

The image job waits for the other three, so an image is only published when everything passes. Dependabot (`.github/dependabot.yml`) opens weekly update PRs for Maven, GitHub Actions and Docker base images.

The smoke test also works against any running instance:
```bash
bash scripts/smoke-test.sh http://localhost:8080
docker build -t ledger . && docker run -p 8080:8080 --network ledger-system_default -e DB_USER=ledger -e DB_PASSWORD=ledger ledger
```

### Review process
Once the first version passed its tests, it went through two independent read-only reviews: one hunting for bugs, one for edge cases. Both independently found the most important issue: the **posting time was read before waiting for locks** (§5.1). The bug review also found that **re-running a tampered month could re-freeze it** (§5.8). The edge-case review found smaller gaps: overflow returning raw 500s, a missing `GET /fx-rates/{base}/{quote}`, Unicode-whitespace names, inconsistent currency case, and rates with trailing zeros being rejected. Each was fixed with a regression test.

A second round ran three more independent reviews in parallel:
- **Logic audit against the brief and this README.** It traced eight scenarios by hand and confirmed all six behaviours. It predicted the reconciliation failure under live traffic, which `ReconciliationConcurrencyTest` reproduced before it was fixed with `FOR NO KEY UPDATE` (§5.2). It also prompted the unique official-close index (§5.8).
- **Edge-case tests.** `EdgeCaseLogicTest` and `ZeroHoldTest` found no further bugs.
- **Dead code and performance.** Removed an unused method, an unused config record and two unused test dependencies. The integrity check now sums entries in one pass instead of once per currency. The Docker build no longer compiles tests. The UI no longer runs the full integrity check on its 10-second timer.

**[docs/logic-walkthrough.md](docs/logic-walkthrough.md)** traces every operation step by step with real numbers, and ends with an edge-case table linking each scenario to the test that proves it.

---

## 9. Project layout

```
src/main/java/com/ledger/
  posting/       PostingEngine (the only writer of money), Leg, LedgerTransaction, Entry, TransactionRepository
  transaction/   TransferService (transfer, FX, deposit, withdrawal), ReversalService
  idempotency/   IdempotencyService
  fx/            FxMath (integer conversion), FxRateService
  audit/         IntegrityCheckService, ReconciliationService, ReconciliationScheduler
  account/       Account, AccountRepository (locking + held-credit queries), AccountService (views)
  api/           REST controllers, request records, response views
  error/         ApiException catalogue, problem+json handler
  config/, db/   Clock, properties, JDBC helpers
src/main/resources/db/migration/V1__ledger_schema.sql   schema, constraints, triggers, seed data
```
