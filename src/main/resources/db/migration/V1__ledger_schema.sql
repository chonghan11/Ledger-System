-- =====================================================================
-- Core double-entry ledger schema.
-- All money columns are BIGINT counts of the currency's minor unit
-- (cents for USD, yen for JPY). No floating point anywhere.
-- =====================================================================

CREATE TABLE currencies (
    code        CHAR(3)  PRIMARY KEY,
    minor_units SMALLINT NOT NULL CHECK (minor_units BETWEEN 0 AND 4)   -- decimal places
);

INSERT INTO currencies (code, minor_units) VALUES
    ('USD', 2), ('SGD', 2), ('EUR', 2), ('GBP', 2), ('JPY', 0);

-- ---------------------------------------------------------------------
-- Accounts. `balance` is a CACHE of SUM(entries.amount) maintained in the
-- same DB transaction as every posting; the entries table is the source
-- of truth. Customer accounts may never go negative (DB-enforced backstop).
-- SYSTEM accounts (EXTERNAL funding source / FX clearing) may go negative.
-- ---------------------------------------------------------------------
CREATE TABLE accounts (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    currency    CHAR(3)      NOT NULL REFERENCES currencies (code),
    type        VARCHAR(10)  NOT NULL CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    system_role VARCHAR(10)  CHECK (system_role IN ('EXTERNAL', 'FX')),
    balance     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT system_role_matches_type
        CHECK ((type = 'SYSTEM') = (system_role IS NOT NULL)),
    CONSTRAINT customer_balance_non_negative
        CHECK (type <> 'CUSTOMER' OR balance >= 0)
);

-- Exactly one EXTERNAL and one FX account per currency.
CREATE UNIQUE INDEX accounts_one_system_role_per_currency
    ON accounts (system_role, currency) WHERE system_role IS NOT NULL;

INSERT INTO accounts (id, name, currency, type, system_role, created_at)
SELECT gen_random_uuid(), 'External ' || c.code, c.code, 'SYSTEM', 'EXTERNAL', TIMESTAMPTZ '2000-01-01 00:00:00+00'
FROM currencies c;

INSERT INTO accounts (id, name, currency, type, system_role, created_at)
SELECT gen_random_uuid(), 'FX clearing ' || c.code, c.code, 'SYSTEM', 'FX', TIMESTAMPTZ '2000-01-01 00:00:00+00'
FROM currencies c;

-- ---------------------------------------------------------------------
-- Transactions: the business event (header). Immutable once written.
-- A reversal is a NEW row pointing at the original via reverses_id.
-- UNIQUE(reverses_id) makes "reverse at most once" a DB guarantee.
-- ---------------------------------------------------------------------
CREATE TABLE transactions (
    id                     UUID         PRIMARY KEY,
    type                   VARCHAR(12)  NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    source_account_id      UUID         NOT NULL REFERENCES accounts (id),
    destination_account_id UUID         NOT NULL REFERENCES accounts (id),
    source_amount          BIGINT       NOT NULL CHECK (source_amount > 0),
    source_currency        CHAR(3)      NOT NULL REFERENCES currencies (code),
    destination_amount     BIGINT       NOT NULL CHECK (destination_amount > 0),
    destination_currency   CHAR(3)      NOT NULL REFERENCES currencies (code),
    fx_rate_scaled         BIGINT       CHECK (fx_rate_scaled > 0),          -- rate x 10^8; NULL if same currency or a reversal
    reverses_id            UUID         UNIQUE REFERENCES transactions (id),
    description            VARCHAR(255),
    created_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT different_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT reversal_has_target CHECK ((type = 'REVERSAL') = (reverses_id IS NOT NULL)),
    -- A cross-currency transfer records its rate. A reversal records none: it negates the original
    -- entries exactly, and the rate lives on the original transaction it points to.
    CONSTRAINT fx_rate_iff_cross_currency
        CHECK (CASE WHEN type = 'REVERSAL' THEN fx_rate_scaled IS NULL
                    ELSE (source_currency = destination_currency) = (fx_rate_scaled IS NULL) END)
);

-- ---------------------------------------------------------------------
-- Entries (postings). Signed amount: positive increases the account
-- balance, negative decreases it. For every transaction the entries of
-- each currency must sum to zero (checked by the integrity job).
-- available_at implements the hold period for incoming transfers.
-- ---------------------------------------------------------------------
CREATE TABLE entries (
    id             BIGSERIAL    PRIMARY KEY,           -- monotonic: tie-breaker for ordering
    transaction_id UUID         NOT NULL REFERENCES transactions (id),
    account_id     UUID         NOT NULL REFERENCES accounts (id),
    amount         BIGINT       NOT NULL CHECK (amount <> 0),
    currency       CHAR(3)      NOT NULL REFERENCES currencies (code),
    available_at   TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT available_not_before_created CHECK (available_at >= created_at)
);

CREATE INDEX entries_account_history ON entries (account_id, created_at, id);
CREATE INDEX entries_transaction     ON entries (transaction_id);
CREATE INDEX entries_held_credits    ON entries (account_id, available_at) WHERE amount > 0;
CREATE INDEX entries_created_at      ON entries (created_at);

-- ---------------------------------------------------------------------
-- The journal is append-only: corrections are made by posting reversals,
-- never by editing history.
-- ---------------------------------------------------------------------
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_append_only
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER transactions_append_only
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- ---------------------------------------------------------------------
-- Idempotency keys. The PRIMARY KEY is the referee when two identical
-- requests race: only one INSERT can win. The row is written in the same
-- DB transaction as the posting, so key and money commit (or roll back)
-- together.
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,                 -- SHA-256 of operation + canonical request
    transaction_id  UUID         REFERENCES transactions (id),
    created_at      TIMESTAMPTZ  NOT NULL
);

-- ---------------------------------------------------------------------
-- FX rates: rate_scaled = units of `quote` per 1 unit of `base`, x 10^8,
-- expressed in MAJOR units (1 USD = 1.345 SGD -> 134500000).
-- ---------------------------------------------------------------------
CREATE TABLE exchange_rates (
    base_currency  CHAR(3)     NOT NULL REFERENCES currencies (code),
    quote_currency CHAR(3)     NOT NULL REFERENCES currencies (code),
    rate_scaled    BIGINT      NOT NULL CHECK (rate_scaled > 0),
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (base_currency, quote_currency),
    CHECK (base_currency <> quote_currency)
);

INSERT INTO exchange_rates (base_currency, quote_currency, rate_scaled, updated_at) VALUES
    ('USD', 'SGD',   134500000, now()),
    ('SGD', 'USD',    74350000, now()),
    ('USD', 'EUR',    92000000, now()),
    ('EUR', 'USD',   108700000, now()),
    ('USD', 'JPY', 15025000000, now()),
    ('JPY', 'USD',      665557, now()),
    ('GBP', 'USD',   127000000, now()),
    ('USD', 'GBP',    78740000, now());

-- ---------------------------------------------------------------------
-- Month-end reconciliation. Each run freezes a statement per account.
-- The FIRST run of a month is its official close, and the partial unique
-- index below makes "exactly one official close per month" a database
-- guarantee even if two runs start at the same moment. Later runs of the
-- same month, and the next month's opening balances, are compared against
-- that close, which detects history that changed after it was closed.
-- ---------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                UUID        PRIMARY KEY,
    run_seq           BIGSERIAL   NOT NULL UNIQUE,
    official_close    BOOLEAN     NOT NULL DEFAULT false,
    period_start      DATE        NOT NULL,
    period_end        DATE        NOT NULL,           -- exclusive
    run_at            TIMESTAMPTZ NOT NULL,
    accounts_checked  INT         NOT NULL,
    discrepancy_count INT         NOT NULL,
    status            VARCHAR(12) NOT NULL CHECK (status IN ('BALANCED', 'DISCREPANCY'))
);

CREATE INDEX reconciliation_runs_period ON reconciliation_runs (period_start, run_seq);
CREATE UNIQUE INDEX reconciliation_one_official_close ON reconciliation_runs (period_start) WHERE official_close;

CREATE TABLE account_statements (
    run_id          UUID        NOT NULL REFERENCES reconciliation_runs (id),
    account_id      UUID        NOT NULL REFERENCES accounts (id),
    period_start    DATE        NOT NULL,
    opening_balance BIGINT      NOT NULL,
    total_credits   BIGINT      NOT NULL,
    total_debits    BIGINT      NOT NULL,                 -- stored as a positive number
    closing_balance BIGINT      NOT NULL,
    entry_count     INT         NOT NULL,
    status          VARCHAR(12) NOT NULL CHECK (status IN ('BALANCED', 'DISCREPANCY')),
    PRIMARY KEY (run_id, account_id)
);

CREATE TABLE reconciliation_discrepancies (
    id             BIGSERIAL   PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES reconciliation_runs (id),
    type           VARCHAR(40) NOT NULL,
    account_id     UUID,
    transaction_id UUID,
    currency       CHAR(3),
    expected       BIGINT,
    actual         BIGINT,
    detail         TEXT        NOT NULL
);
