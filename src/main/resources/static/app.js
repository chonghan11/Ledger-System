// Ledger UI: a thin client over the REST API. All business rules live in the backend; this page only
// converts what you type into integer minor units, calls the API, and shows the result (or the error code).
'use strict';

const MAX_AMOUNT = 10n ** 15n;          // same per-request cap as the backend
const REFRESH_MS = 10_000;              // re-poll balances so holds visibly expire

const state = {
  currencies: {},                       // code -> decimal places
  accounts: [],
  byId: new Map(),
  selectedId: null,
  history: [],
  expanded: new Set(),                  // transaction ids whose legs are shown
  legsCache: new Map(),                 // transaction id -> entries (immutable, so cached forever)
  legsOpen: new Set(),                  // transaction ids whose "How it's booked" section is open (survives refresh)
  rates: [],
  lastRequest: null,                    // {path, key, body} for the "replay" demo
};

// ------------------------------------------------------------------ helpers

const $ = (sel, root = document) => root.querySelector(sel);
const el = (tag, attrs = {}, ...children) => {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null && v !== false) node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));   // text nodes: no HTML injection
  }
  return node;
};

/** Parses "12.50" into 1250 (minor units) by string handling only: never parseFloat(x) * 100. */
function toMinor(text, decimals) {
  const s = String(text).trim().replaceAll(',', '');
  const pattern = decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`);
  if (!pattern.test(s)) {
    throw new Error(decimals === 0
      ? 'Enter a whole number (this currency has no decimal places)'
      : `Enter a positive amount with at most ${decimals} decimal places`);
  }
  const [whole, frac = ''] = s.split('.');
  const minor = BigInt(whole) * 10n ** BigInt(decimals) + BigInt(frac.padEnd(decimals, '0') || '0');
  if (minor <= 0n) throw new Error('Amount must be greater than zero');
  if (minor > MAX_AMOUNT) throw new Error('Amount is above the per-request limit');
  return Number(minor);                // <= 10^15, well inside the exactly-representable integer range
}

/** Formats minor units as a decimal string with thousands separators, e.g. 123456 -> "1,234.56". */
function fmt(minor, currency, { sign = false } = {}) {
  const decimals = state.currencies[currency] ?? 2;
  const negative = minor < 0;
  const digits = String(Math.abs(minor)).padStart(decimals + 1, '0');
  const whole = digits.slice(0, digits.length - decimals).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const frac = decimals ? '.' + digits.slice(-decimals) : '';
  const prefix = negative ? '−' : (sign && minor > 0 ? '+' : '');
  return `${prefix}${whole}${frac}`;
}

/** Same integer conversion as the backend's FxMath (BigInt, HALF_EVEN), used only for the preview. */
function convertPreview(amountMinor, srcDecimals, dstDecimals, rateScaled) {
  const num = BigInt(amountMinor) * BigInt(rateScaled) * 10n ** BigInt(dstDecimals);
  const den = 100_000_000n * 10n ** BigInt(srcDecimals);
  let q = num / den;
  const twiceRem = (num % den) * 2n;
  if (twiceRem > den || (twiceRem === den && q % 2n === 1n)) q += 1n;
  return Number(q);
}

const shortId = (id) => id ? id.slice(0, 8) : '';
const timeOf = (iso) => new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
const accountName = (id) => state.byId.get(id)?.name ?? shortId(id);

function minutesLeft(iso) {
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return null;
  const m = Math.floor(ms / 60000), s = Math.floor((ms % 60000) / 1000);
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

// ------------------------------------------------------------------ API

class ApiError extends Error {
  constructor(status, code, detail) { super(detail); this.status = status; this.code = code; }
}

async function api(path, { method = 'GET', body, idempotencyKey } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, data?.code ?? `HTTP_${res.status}`, data?.detail ?? res.statusText);
  return { data, replayed: res.headers.get('Idempotent-Replayed') === 'true' };
}

// ------------------------------------------------------------------ notices

function notify(kind, message, { code, action } = {}) {
  const box = $('#notice');
  box.className = `notice ${kind}`;
  box.replaceChildren(
    el('div', {}, code ? el('span', { class: 'code' }, code) : null, message),
    el('div', {}, action ?? null, el('button', { class: 'close', title: 'Dismiss', onclick: () => box.classList.add('hidden') }, '×')),
  );
}

function notifyError(err) {
  if (err instanceof ApiError) notify('err', err.message, { code: err.code });
  else notify('err', err.message);
}

// ------------------------------------------------------------------ loading

async function loadCurrencies() {
  const { data } = await api('/currencies');
  state.currencies = Object.fromEntries(data.map((c) => [c.code, c.minorUnits]));
  const options = data.map((c) => el('option', { value: c.code }, c.code));
  $('#create-currency').replaceChildren(...options.map((o) => o.cloneNode(true)));
  $('#rate-form [name=base]').replaceChildren(...options.map((o) => o.cloneNode(true)));
  $('#rate-form [name=quote]').replaceChildren(...options.map((o) => o.cloneNode(true)));

  // UI convenience only: pre-select SGD (the API itself has no default and always requires a currency).
  const DEFAULT_CURRENCY = 'SGD';
  if (state.currencies[DEFAULT_CURRENCY] !== undefined) {
    $('#create-currency').value = DEFAULT_CURRENCY;
    $('#rate-form [name=base]').value = DEFAULT_CURRENCY;
    $('#rate-form [name=quote]').value = 'USD';
  }
}

async function loadAccounts() {
  const { data } = await api('/accounts');
  state.accounts = data;
  state.byId = new Map(data.map((a) => [a.id, a]));
  renderAccounts();
}

async function loadRates() {
  const { data } = await api('/fx-rates');
  state.rates = data;
  renderRates();
}

async function loadSelected() {
  if (!state.selectedId) return;
  const [{ data: account }, { data: history }] = await Promise.all([
    api(`/accounts/${state.selectedId}`),
    api(`/accounts/${state.selectedId}/transactions`),
  ]);
  state.byId.set(account.id, account);
  state.history = history;
  renderDetail(account);
  await renderHistory();
}

/**
 * Reloads balances and history. The integrity check scans the whole ledger, so it runs after the user's own
 * actions (and on demand), not on every background refresh.
 */
async function refreshAll({ integrity = true } = {}) {
  await loadAccounts();
  await loadSelected();
  if (integrity) await runIntegrity();
}

// ------------------------------------------------------------------ rendering: accounts

function renderAccounts() {
  const showSystem = $('#show-system').checked;
  const rows = state.accounts
    .filter((a) => showSystem || a.type === 'CUSTOMER')
    .map((a) => el('tr', {
        class: [a.type === 'SYSTEM' ? 'system' : '', a.id === state.selectedId ? 'selected' : ''].join(' '),
        onclick: () => select(a.id),
      },
      el('td', {}, a.name, el('div', { class: 'sub' }, a.type === 'SYSTEM' ? `system · ${a.systemRole}` : a.currency)),
      el('td', { class: 'num' }, fmt(a.balance, a.currency), ' ', el('span', { class: 'muted small' }, a.currency)),
      el('td', { class: 'num' }, a.type === 'SYSTEM' ? '—' : fmt(a.availableBalance, a.currency),
        a.heldBalance > 0 ? el('div', { class: 'sub held' }, `${fmt(a.heldBalance, a.currency)} on hold`) : null),
    ));
  $('#accounts-body').replaceChildren(...rows);
  $('#accounts-empty').classList.toggle('hidden', state.accounts.some((a) => a.type === 'CUSTOMER'));
}

async function select(id) {
  state.selectedId = id;
  state.expanded.clear();
  renderAccounts();
  try { await loadSelected(); } catch (e) { notifyError(e); }
}

// ------------------------------------------------------------------ rendering: selected account

function renderDetail(a) {
  $('#no-selection').classList.add('hidden');
  $('#detail').classList.remove('hidden');
  $('#d-name').textContent = a.name;
  $('#d-id').textContent = a.id;
  $('#d-currency').textContent = a.type === 'SYSTEM' ? `${a.currency} · system ${a.systemRole}` : a.currency;
  $('#d-balance').textContent = fmt(a.balance, a.currency);
  $('#d-available').textContent = a.type === 'SYSTEM' ? '—' : fmt(a.availableBalance, a.currency);
  $('#d-held').textContent = fmt(a.heldBalance, a.currency);
  $('#d-held').classList.toggle('zero', a.heldBalance === 0);   // amber only when something is actually held
  document.querySelectorAll('[data-ccy]').forEach((n) => { n.textContent = `(${a.currency})`; });
  document.querySelectorAll('.action-form input[name=amount]').forEach((n) => {
    n.placeholder = (state.currencies[a.currency] ?? 2) === 0 ? '0' : '0.00';
  });

  // Customers can move money; system accounts are read-only.
  $('#actions-card').classList.toggle('hidden', a.type === 'SYSTEM');

  const to = $('#transfer-form [name=to]');
  const previous = to.value;
  const targets = state.accounts.filter((x) => x.type === 'CUSTOMER' && x.id !== a.id);
  to.replaceChildren(...targets.map((x) => el('option', { value: x.id }, `${x.name} (${x.currency})`)));
  if (targets.some((x) => x.id === previous)) to.value = previous;
  updateFxPreview();
}

async function renderHistory() {
  const account = state.byId.get(state.selectedId);
  const rows = [];
  for (const h of state.history) {
    const reversed = Boolean(h.reversedByTransactionId);
    const left = h.amount > 0 && !reversed ? minutesLeft(h.availableAt) : null;
    const details = [];
    const isReversal = h.type === 'REVERSAL';
    // The server's default reason is "Reversal of <full id>"; the short id is shown below, so don't repeat it.
    const defaultReason = isReversal && h.description === `Reversal of ${h.reversesTransactionId}`;
    const title = isReversal ? (defaultReason ? 'Reversal' : h.description) : h.description;
    if (title) details.push(el('div', {}, title));
    const direction = isReversal
      ? (h.amount < 0 ? 'returned to ' : 'returned by ')    // money going back, not a new payment
      : (h.amount < 0 ? 'to ' : 'from ');
    details.push(el('div', { class: 'muted small' },
      direction, accountName(h.counterpartyAccountId),
      h.reversesTransactionId ? ` · reverses ${shortId(h.reversesTransactionId)}` : ''));

    const status = [];
    if (left) status.push(el('span', { class: 'badge held', title: `spendable at ${timeOf(h.availableAt)}` }, `held · ${left}`));
    if (reversed) status.push(el('span', { class: 'badge reversed' }, 'reversed'));
    // Only the SENDER can take a transfer back, so the button appears on outgoing transfers only. A recipient
    // who wants to return money sends a normal transfer; reversing deposits/withdrawals (chargebacks, failed ATM
    // payouts) is a back-office correction, available through the API but not offered to customers here.
    const canReverse = h.type === 'TRANSFER' && h.amount < 0 && !reversed && account?.type === 'CUSTOMER';
    if (canReverse) {
      status.push(el('button', {
        class: 'danger-link',
        title: 'Take this money back. Guaranteed while it is still on hold for the recipient; afterwards only if they have not spent it.',
        onclick: (ev) => { ev.stopPropagation(); reverse(h.transactionId); },
      }, 'Reverse'));
    }

    rows.push(el('tr', {
        class: `row ${reversed ? 'is-reversed' : ''}`,
        onclick: () => toggleLegs(h.transactionId),
      },
      el('td', { class: 'when' }, timeOf(h.createdAt)),
      el('td', {}, el('span', { class: `badge type-${h.type}` }, h.type.toLowerCase())),
      el('td', {}, details),
      el('td', { class: `num amount ${h.amount < 0 ? 'neg' : 'pos'}` }, fmt(h.amount, h.currency, { sign: true })),
      el('td', { class: 'num' }, fmt(h.runningBalance, h.currency)),
      el('td', { class: 'num' }, status),
    ));

    if (state.expanded.has(h.transactionId)) rows.push(await legsRow(h.transactionId));
  }
  $('#history-body').replaceChildren(...rows);
  $('#history-empty').classList.toggle('hidden', state.history.length > 0);
}

async function legsRow(txId) {
  if (!state.legsCache.has(txId)) {
    const { data } = await api(`/transactions/${txId}`);
    state.legsCache.set(txId, data);
  }
  const tx = state.legsCache.get(txId);
  const meta = el('div', { class: 'muted small' },
    `Transaction ${tx.id}`,
    tx.reversedByTransactionId ? ` · reversed by ${shortId(tx.reversedByTransactionId)}` : '');
  return el('tr', { class: 'legs' }, el('td', { colspan: 6 }, meta, flowView(tx), legsView(tx)));
}

// ---- transaction details: plain-English flow first, the double-entry legs underneath ----

const money = (minor, currency) => `${fmt(minor, currency)} ${currency}`;
/** Drops trailing zeros after the decimal point only: "1.34500000" -> "1.345", "13.40" -> "13.4", "100" stays "100". */
const trimFraction = (s) => (s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s);
const trimRate = trimFraction;
const groupThousands = (s) => s.replace(/^(\d+)/, (w) => w.replace(/\B(?=(\d{3})+(?!\d))/g, ','));

/** Exact (unrounded) amount x rate in major units, e.g. 2500 cents x 1.345 -> "33.625". BigInt: no floats. */
function exactConverted(amountMinor, srcDecimals, rate) {
  const scale = 8 + srcDecimals;                                   // rate has 8 decimals, amount has srcDecimals
  const digits = (BigInt(amountMinor) * BigInt(rate.replace('.', ''))).toString().padStart(scale + 1, '0');
  const whole = digits.slice(0, -scale);
  const frac = digits.slice(-scale).replace(/0+$/, '');
  return groupThousands(frac ? `${whole}.${frac}` : whole);
}

function flowRow(label, amount, { cls = '', note = '', rate = false } = {}) {
  return el('div', { class: `flow-row ${rate ? 'flow-rate' : ''}` },
    el('span', {}, label),
    el('span', { class: `num ${cls}` }, amount),
    el('span', { class: 'muted small' }, note));
}

function flowView(tx) {
  const src = accountName(tx.sourceAccountId);
  const dst = accountName(tx.destinationAccountId);
  const sent = money(tx.sourceAmount, tx.sourceCurrency);
  const received = money(tx.destinationAmount, tx.destinationCurrency);
  const credit = tx.entries.find((e) => e.accountId === tx.destinationAccountId && e.amount > 0);
  const held = credit && new Date(credit.availableAt) > new Date(tx.createdAt);
  const receivedNote = held ? `spendable from ${timeOf(credit.availableAt)}` : 'spendable immediately';
  const rows = [];

  if (tx.type === 'DEPOSIT') {
    rows.push(flowRow(`${dst} receives`, received, { cls: 'pos', note: 'money coming into the ledger from outside' }));
  } else if (tx.type === 'WITHDRAWAL') {
    rows.push(flowRow(`${src} pays out`, sent, { note: 'money leaving the ledger' }));
  } else if (tx.type === 'REVERSAL') {
    rows.push(flowRow(`Reverses transaction ${shortId(tx.reversesTransactionId)}`, '', { note: 'a new entry; the original is untouched' }));
    rows.push(flowRow(`${src} gives back`, sent));
    rows.push(flowRow(`${dst} gets back`, received, {
      cls: 'pos',
      note: tx.sourceCurrency !== tx.destinationCurrency
        ? 'undone at the original rate: exactly what was sent' : 'spendable immediately',
    }));
  } else {
    rows.push(flowRow(`${src} sends`, sent));
    if (tx.fxRate) {
      const exact = exactConverted(tx.sourceAmount, state.currencies[tx.sourceCurrency] ?? 2, tx.fxRate);
      const rounded = fmt(tx.destinationAmount, tx.destinationCurrency);
      const wasRounded = exact !== trimFraction(rounded);
      const unit = (state.currencies[tx.destinationCurrency] ?? 2) === 0 ? `whole ${tx.destinationCurrency}` : 'cent';
      rows.push(flowRow(`× ${trimRate(tx.fxRate)}  (${tx.sourceCurrency} → ${tx.destinationCurrency} rate)`,
        wasRounded ? `= ${exact} → ${received}` : `= ${received}`,
        { rate: true, note: wasRounded ? `rounded half-even to the ${unit}` : 'exact, no rounding needed' }));
    }
    rows.push(flowRow(`${dst} receives`, received, { cls: 'pos', note: receivedNote }));
  }
  return el('div', { class: 'flow' }, rows);
}

function legsView(tx) {
  const net = {};
  for (const e of tx.entries) net[e.currency] = (net[e.currency] ?? 0) + e.amount;
  const caption = tx.fxRate
    ? `Each currency balances on its own: the ledger's FX clearing accounts take in the ${tx.sourceCurrency} and pay out the ${tx.destinationCurrency}.`
    : 'Every transaction is recorded as entries that add up to zero.';
  const details = el('details', {
      class: 'legs-details',
      open: state.legsOpen.has(tx.id),
      ontoggle: (ev) => { if (ev.currentTarget.open) state.legsOpen.add(tx.id); else state.legsOpen.delete(tx.id); },
    },
    el('summary', {}, `How it's booked: ${tx.entries.length} double-entry legs`),
    el('div', { class: 'muted small' }, caption),
    el('table', { class: 'legs-table' },
      el('tbody', {},
        tx.entries.map((e) => el('tr', {},
          el('td', {}, accountName(e.accountId)),
          el('td', { class: `num ${e.amount < 0 ? 'neg' : 'pos'}` }, fmt(e.amount, e.currency, { sign: true }), ' ', e.currency),
        )),
        el('tr', { class: 'net' },
          el('td', {}, 'Net per currency'),
          el('td', { class: 'num' }, Object.entries(net).map(([c, v]) => `${c} ${fmt(v, c)}${v === 0 ? ' ✓' : ''}`).join(' · ')),
        ),
      )));
  return details;
}

async function toggleLegs(txId) {
  if (state.expanded.has(txId)) state.expanded.delete(txId); else state.expanded.add(txId);
  try { await renderHistory(); } catch (e) { notifyError(e); }
}

function updateFxPreview() {
  const out = $('#fx-preview');
  const src = state.byId.get(state.selectedId);
  const dst = state.byId.get($('#transfer-form [name=to]').value);
  out.textContent = '';
  if (!src || !dst || src.currency === dst.currency) return;
  const rate = state.rates.find((r) => r.base === src.currency && r.quote === dst.currency);
  if (!rate) { out.textContent = `No ${src.currency}→${dst.currency} rate configured: this transfer would be rejected.`; return; }
  let amount;
  try { amount = toMinor($('#transfer-form [name=amount]').value, state.currencies[src.currency]); } catch { amount = null; }
  out.textContent = amount
    ? `Recipient gets ≈ ${fmt(convertPreview(amount, state.currencies[src.currency], state.currencies[dst.currency], rate.rateScaled), dst.currency)} ${dst.currency} at ${rate.rate} (final figure is computed by the server)`
    : `Cross-currency: converted at ${src.currency}→${dst.currency} ${rate.rate}`;
}

// ------------------------------------------------------------------ money actions

/** Sends a money-moving request with a fresh Idempotency-Key and remembers it for the replay demo. */
async function send(path, body) {
  const key = `ui-${crypto.randomUUID()}`;
  state.lastRequest = { path, key, body };
  const result = await api(path, { method: 'POST', body, idempotencyKey: key });
  return result;
}

async function replayLast() {
  const r = state.lastRequest;
  if (!r) return;
  try {
    const { data, replayed } = await api(r.path, { method: 'POST', body: r.body, idempotencyKey: r.key });
    notify('ok', replayed
      ? `Replayed with the same Idempotency-Key: the server returned the original transaction ${shortId(data.id)} and moved no money.`
      : `Posted ${shortId(data.id)}.`);
    await refreshAll();
  } catch (e) { notifyError(e); }
}

function replayButton() {
  return el('button', { class: 'link', title: 'Send exactly the same request again, with the same Idempotency-Key', onclick: replayLast },
    'Send same request again');
}

async function handleMoneyForm(ev, kind) {
  ev.preventDefault();
  const form = ev.currentTarget;
  const account = state.byId.get(state.selectedId);
  const button = form.querySelector('button[type=submit]');
  try {
    const amount = toMinor(form.amount.value, state.currencies[account.currency]);
    const description = form.description.value.trim() || undefined;
    button.disabled = true;
    let result, message;
    if (kind === 'transfer') {
      const to = form.to.value;
      if (!to) throw new Error('Create a second account to transfer to');
      result = await send('/transactions',
        { fromAccountId: account.id, toAccountId: to, amount, currency: account.currency, description });
      const tx = result.data;
      message = `Sent ${fmt(tx.sourceAmount, tx.sourceCurrency)} ${tx.sourceCurrency} to ${accountName(to)}`
        + (tx.fxRate ? ` (they receive ${fmt(tx.destinationAmount, tx.destinationCurrency)} ${tx.destinationCurrency} at ${tx.fxRate})` : '')
        + '. The money is on hold for them for 10 minutes.';
    } else {
      result = await send(`/accounts/${account.id}/${kind === 'deposit' ? 'deposits' : 'withdrawals'}`,
        { amount, currency: account.currency, description });
      message = `${kind === 'deposit' ? 'Deposited' : 'Withdrew'} ${fmt(amount, account.currency)} ${account.currency}.`;
    }
    notify('ok', message, { action: replayButton() });
    form.reset();
    await refreshAll();
  } catch (e) {
    notifyError(e);
  } finally {
    button.disabled = false;
    updateFxPreview();
  }
}

async function reverse(txId) {
  const reason = prompt('Reason for the reversal (optional):', '');
  if (reason === null) return;
  try {
    const { data } = await api(`/transactions/${txId}/reversal`, { method: 'POST', body: { reason: reason || undefined } });
    state.legsCache.delete(txId);   // its status changed to REVERSED
    notify('ok', `Reversed ${shortId(txId)} with new transaction ${shortId(data.id)}. History was not edited: the reversal is a new entry.`);
    await refreshAll();
  } catch (e) { notifyError(e); }
}

// ------------------------------------------------------------------ operations panels

let integrityRunning = false;
let integrityRerun = false;
const MIN_LOADING_MS = 400;   // the check often takes ~10 ms; keep the loading state visible long enough to notice

async function runIntegrity() {
  // While a check is in flight, further requests collapse into ONE follow-up run, so a check requested
  // after a transfer always reflects that transfer, and repeated clicks never stack up.
  if (integrityRunning) { integrityRerun = true; return; }
  integrityRunning = true;
  const out = $('#integrity-out');
  const pill = $('#health-pill');
  const button = $('#integrity-run');
  const bar = $('#integrity-progress');
  button.disabled = true;
  button.textContent = 'Checking…';
  bar.classList.remove('hidden');
  pill.textContent = 'checking…';
  pill.className = 'pill';
  const started = performance.now();
  try {
    const { data } = await api('/admin/integrity-check');
    const tookMs = Math.round(performance.now() - started);       // real server round trip, reported honestly
    const remaining = MIN_LOADING_MS - (performance.now() - started);
    if (remaining > 0) await new Promise((resolve) => setTimeout(resolve, remaining));

    pill.textContent = data.healthy ? 'ledger healthy' : `${data.discrepancies.length} discrepancies`;
    pill.className = `pill ${data.healthy ? 'ok' : 'bad'}`;
    const used = data.currencies.filter((c) => c.customerBalances || c.externalBalance || c.fxBalance);
    out.className = '';
    out.replaceChildren(
      el('div', { class: 'kv' },
        el('div', {}, 'Status ', el('strong', { class: data.healthy ? 'pos' : 'bad' }, data.healthy ? 'healthy' : 'discrepancies found')),
        el('div', {}, 'Accounts ', el('strong', {}, data.accountsChecked)),
        el('div', {}, 'Transactions ', el('strong', {}, data.transactionsChecked)),
        el('div', {}, 'Entries ', el('strong', {}, data.entriesChecked)),
      ),
      el('div', { class: 'muted small checked-at' },
        `Last checked ${new Date(data.checkedAt).toLocaleTimeString()} · took ${tookMs} ms`),
      ...data.discrepancies.map(discrepancyNode),   // spread: replaceChildren() does not flatten arrays
      used.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table compact' },
        el('thead', {}, el('tr', {},
          el('th', {}, 'Ccy'), el('th', { class: 'num' }, 'Customers'), el('th', { class: 'num' }, 'FX pos.'),
          el('th', { class: 'num' }, 'Net in'), el('th', { class: 'num', title: 'sum of all entries' }, 'Σ'))),
        el('tbody', {}, used.map((c) => el('tr', {},
          el('td', {}, c.currency),
          // customers + FX position must equal net money deposited; flag the row's figure if it doesn't
          el('td', { class: `num ${c.customerBalances + c.fxBalance === c.netDeposited ? '' : 'bad'}` },
            fmt(c.customerBalances, c.currency)),
          el('td', { class: 'num' }, fmt(c.fxBalance, c.currency, { sign: true })),
          el('td', { class: 'num' }, fmt(c.netDeposited, c.currency)),
          el('td', { class: `num ${c.sumOfEntries === 0 ? 'pos' : 'bad'}` }, c.sumOfEntries === 0 ? '0 ✓' : c.sumOfEntries),
        ))))) : el('div', { class: 'muted small' }, 'No money in the ledger yet.'),
      el('p', { class: 'muted small' }, 'Per currency: customers + FX position = net money deposited, and all entries sum to 0.'),
    );
  } catch (e) {
    pill.textContent = 'check failed';
    pill.className = 'pill bad';
    notifyError(e);
  } finally {
    button.disabled = false;
    button.textContent = 'Run check';
    bar.classList.add('hidden');
    integrityRunning = false;
    if (integrityRerun) { integrityRerun = false; runIntegrity(); }
  }
}

function discrepancyNode(d) {
  const amount = (v) => (d.currency ? `${fmt(v, d.currency)} ${d.currency}` : v);
  return el('div', { class: 'discrepancy' },
    el('span', { class: 'code' }, d.type), ' ',
    d.accountId ? `${accountName(d.accountId)} · ` : '',
    d.transactionId ? `tx ${shortId(d.transactionId)} · ` : '',
    d.detail,
    d.expected !== null && d.expected !== undefined ? ` (expected ${amount(d.expected)}, actual ${amount(d.actual)})` : '');
}

async function runReconciliation(ev) {
  ev.preventDefault();
  const period = ev.currentTarget.period.value;
  const out = $('#recon-out');
  try {
    const { data } = await api('/admin/reconciliations', { method: 'POST', body: { period } });
    const customer = data.statements.filter((s) => s.accountType === 'CUSTOMER');
    out.className = 'table-wrap';
    out.replaceChildren(
      el('div', { class: 'kv' },
        el('div', {}, 'Period ', el('strong', {}, data.period)),
        el('div', {}, 'Status ', el('strong', { class: data.status === 'BALANCED' ? 'pos' : 'bad' }, data.status.toLowerCase())),
        el('div', {}, 'Run ', el('strong', {}, data.officialClose ? 'official close' : 're-run (checked against the official close)')),
        el('div', {}, 'Accounts ', el('strong', {}, data.accountsChecked)),
      ),
      ...data.discrepancies.map(discrepancyNode),   // spread: replaceChildren() does not flatten arrays
      customer.length ? el('details', { open: customer.length <= 8 },
        el('summary', {}, `Customer statements (${customer.length})`),
        el('table', { class: 'table compact' },
          el('thead', {}, el('tr', {}, el('th', {}, 'Account'), el('th', { class: 'num' }, 'Opening'),
            el('th', { class: 'num' }, 'In'), el('th', { class: 'num' }, 'Out'), el('th', { class: 'num' }, 'Closing'))),
          el('tbody', {}, customer.map((s) => el('tr', {},
            el('td', {}, accountName(s.accountId), ' ', el('span', { class: 'muted small' }, s.currency)),
            el('td', { class: 'num' }, fmt(s.openingBalance, s.currency)),
            el('td', { class: 'num pos' }, fmt(s.totalCredits, s.currency)),
            el('td', { class: 'num neg' }, fmt(s.totalDebits, s.currency)),
            el('td', { class: 'num' }, fmt(s.closingBalance, s.currency)),
          ))))) : el('div', { class: 'muted small' }, 'No customer accounts existed in this period.'),
    );
  } catch (e) {
    out.className = 'muted small';
    out.replaceChildren(el('div', { class: 'discrepancy' }, el('span', { class: 'code' }, e.code ?? 'ERROR'), ' ', e.message));
  }
}

function renderRates() {
  $('#rates-body').replaceChildren(...state.rates.map((r) => el('tr', {},
    el('td', {}, `${r.base} → ${r.quote}`),
    el('td', { class: 'num' }, r.rate),
    el('td', { class: 'muted small' }, timeOf(r.updatedAt)),
  )));
}

async function setRate(ev) {
  ev.preventDefault();
  const f = ev.currentTarget;
  try {
    await api(`/fx-rates/${f.base.value}/${f.quote.value}`, { method: 'PUT', body: { rate: f.rate.value.trim() } });
    notify('ok', `Rate ${f.base.value}→${f.quote.value} set to ${f.rate.value.trim()}.`);
    f.rate.value = '';
    await loadRates();
    updateFxPreview();
  } catch (e) { notifyError(e); }
}

// ------------------------------------------------------------------ wiring

function wire() {
  $('#show-system').addEventListener('change', renderAccounts);

  $('#create-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const f = ev.currentTarget;
    try {
      const { data } = await api('/accounts', { method: 'POST', body: { name: f.name.value, currency: f.currency.value } });
      f.name.value = '';
      await loadAccounts();
      await select(data.id);
      notify('ok', `Created ${data.name} (${data.currency}). Deposit some money to get started.`);
      document.querySelector('.tab[data-tab=deposit]').click();
    } catch (e) { notifyError(e); }
  });

  document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === tab));
    document.querySelectorAll('.action-form').forEach((f) => f.classList.toggle('hidden', f.dataset.panel !== tab.dataset.tab));
  }));

  $('#transfer-form').addEventListener('submit', (ev) => handleMoneyForm(ev, 'transfer'));
  $('#deposit-form').addEventListener('submit', (ev) => handleMoneyForm(ev, 'deposit'));
  $('#withdraw-form').addEventListener('submit', (ev) => handleMoneyForm(ev, 'withdraw'));
  $('#transfer-form [name=to]').addEventListener('change', updateFxPreview);
  $('#transfer-form [name=amount]').addEventListener('input', updateFxPreview);

  $('#integrity-run').addEventListener('click', runIntegrity);
  $('#health-pill').addEventListener('click', runIntegrity);
  $('#recon-form').addEventListener('submit', runReconciliation);
  $('#rate-form').addEventListener('submit', setRate);

  // Default the reconciliation month to last month (the most recent one that has ended).
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  $('#recon-form [name=period]').value = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

async function start() {
  wire();
  try {
    await loadCurrencies();
    await Promise.all([loadAccounts(), loadRates()]);
    const firstCustomer = state.accounts.find((a) => a.type === 'CUSTOMER');
    if (firstCustomer) await select(firstCustomer.id);
    await runIntegrity();
  } catch (e) { notifyError(e); }

  // Background refresh so holds visibly expire: skipped while the tab is hidden, and chained with setTimeout
  // so a slow response can never cause overlapping refreshes.
  const poll = async () => {
    if (!document.hidden) {
      try { await refreshAll({ integrity: false }); } catch { /* transient: next tick retries */ }
    }
    setTimeout(poll, REFRESH_MS);
  };
  setTimeout(poll, REFRESH_MS);
}

start();
