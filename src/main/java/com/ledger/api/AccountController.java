package com.ledger.api;

import com.ledger.account.AccountService;
import com.ledger.posting.TransactionRepository;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accounts;
    private final TransferService transfers;
    private final TransactionRepository transactions;

    public AccountController(AccountService accounts, TransferService transfers, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.transactions = transactions;
    }

    @PostMapping
    public ResponseEntity<AccountService.AccountView> create(@Valid @RequestBody Requests.CreateAccount req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.create(req.name(), req.currency()));
    }

    /** All accounts (customers first, then the EXTERNAL / FX system accounts). */
    @GetMapping
    public List<AccountService.AccountView> list() {
        return accounts.list();
    }

    @GetMapping("/{id}")
    public AccountService.AccountView get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @GetMapping("/{id}/transactions")
    public List<AccountService.HistoryItem> history(@PathVariable UUID id) {
        return accounts.history(id);
    }

    @PostMapping("/{id}/deposits")
    public ResponseEntity<TransactionView> deposit(@PathVariable UUID id,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody Requests.Cash req) {
        return TransactionController.respond(transfers.deposit(key, id, req.amount(), req.currency(), req.description()),
                transactions);
    }

    @PostMapping("/{id}/withdrawals")
    public ResponseEntity<TransactionView> withdraw(@PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody Requests.Cash req) {
        return TransactionController.respond(transfers.withdraw(key, id, req.amount(), req.currency(), req.description()),
                transactions);
    }
}
