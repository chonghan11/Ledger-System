package com.ledger.api;

import com.ledger.transaction.TransferService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request bodies. Amounts are {@code long} integers of the currency's minor unit
 * (e.g. 1234 = 12.34 USD). A JSON value such as 12.5 is rejected as malformed: no fractions anywhere.
 * Currency codes are case-insensitive.
 */
public final class Requests {

    private static final String CURRENCY = "[A-Za-z]{3}";
    private static final String CURRENCY_MSG = "must be a 3-letter ISO currency code";

    private Requests() {}

    public record CreateAccount(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = CURRENCY, message = CURRENCY_MSG) String currency) {}

    public record Transfer(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @Positive @Max(TransferService.MAX_AMOUNT) Long amount,
            @NotNull @Pattern(regexp = CURRENCY, message = CURRENCY_MSG) String currency,
            @Size(max = 255) String description) {}

    public record Cash(
            @NotNull @Positive @Max(TransferService.MAX_AMOUNT) Long amount,
            @NotNull @Pattern(regexp = CURRENCY, message = CURRENCY_MSG) String currency,
            @Size(max = 255) String description) {}

    public record Reversal(@Size(max = 255) String reason) {}

    public record FxRate(@NotBlank @Size(max = 30) String rate) {}

    public record Reconciliation(
            @NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be a month in YYYY-MM format") String period) {}
}
