package com.ledger.api;

import com.ledger.error.ApiException;
import com.ledger.posting.LedgerTransaction;
import com.ledger.posting.TransactionRepository;
import com.ledger.transaction.ReversalService;
import com.ledger.transaction.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    /** Response header set when a request was a repeat and nothing new was posted. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transfers;
    private final ReversalService reversals;
    private final TransactionRepository transactions;

    public TransactionController(TransferService transfers, ReversalService reversals, TransactionRepository transactions) {
        this.transfers = transfers;
        this.reversals = reversals;
        this.transactions = transactions;
    }

    /** Transfer money. {@code Idempotency-Key} is mandatory: a retry with the same key never moves money twice. */
    @PostMapping
    public ResponseEntity<TransactionView> transfer(@RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody Requests.Transfer req) {
        return respond(transfers.transfer(key, req.fromAccountId(), req.toAccountId(), req.amount(),
                req.currency(), req.description()), transactions);
    }

    @GetMapping("/{id}")
    public TransactionView get(@PathVariable UUID id) {
        LedgerTransaction tx = transactions.findById(id).orElseThrow(() -> ApiException.notFound("transaction", id));
        return view(tx, transactions);
    }

    /** Naturally idempotent: a transaction can be reversed once; a second attempt returns 409 ALREADY_REVERSED. */
    @PostMapping("/{id}/reversal")
    public ResponseEntity<TransactionView> reverse(@PathVariable UUID id,
                                                   @Valid @RequestBody(required = false) Requests.Reversal req) {
        LedgerTransaction reversal = reversals.reverse(id, req == null ? null : req.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(reversal, transactions));
    }

    static ResponseEntity<TransactionView> respond(TransferService.Result result, TransactionRepository transactions) {
        // A replay answers exactly like the original request did (201, same transaction id),
        // flagged by a header so clients/ops can tell it apart.
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(view(result.transaction(), transactions));
    }

    static TransactionView view(LedgerTransaction tx, TransactionRepository transactions) {
        return TransactionView.of(tx, transactions.entriesOf(tx.id()), transactions.findReversalOf(tx.id()).orElse(null));
    }
}
