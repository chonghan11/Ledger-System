package com.ledger;

import com.ledger.posting.LedgerTransaction;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the hold switched off (ledger.hold-period=PT0S) incoming money is spendable at once, which shows exactly
 * what the hold protects against: the recipient can spend before a mistaken transfer is noticed, and the
 * reversal then has nothing to claw back.
 */
class ZeroHoldTest extends IntegrationTest {

    @DynamicPropertySource
    static void noHold(DynamicPropertyRegistry registry) {
        registry.add("ledger.hold-period", () -> "PT0S");
    }

    @Test
    void withoutAHoldMoneyIsSpendableAtOnceAndAReversalCanBeTooLate() {
        UUID a = fundedAccount("USD", 500);
        UUID b = newAccount("USD");
        UUID c = newAccount("USD");
        LedgerTransaction mistake = transfer(a, b, 500, "USD");

        assertThat(available(b)).isEqualTo(500);         // no hold: immediately spendable
        transfer(b, c, 500, "USD");                       // B spends it straight away

        assertApiError("INSUFFICIENT_FUNDS", () -> reversals.reverse(mistake.id(), null));
        assertThat(balance(a)).isZero();                  // A cannot get the money back from the ledger
    }
}
