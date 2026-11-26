package com.ledger.api;

import com.ledger.account.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CurrencyController {

    private final AccountService accounts;

    public CurrencyController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Supported currencies and their decimal places (minor units), e.g. USD 2, JPY 0. */
    @GetMapping("/currencies")
    public List<AccountService.CurrencyView> currencies() {
        return accounts.currencies();
    }
}
