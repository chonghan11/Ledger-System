package com.ledger;

import com.ledger.support.IntegrationTest;
import com.ledger.transaction.TransferService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends IntegrationTest {

    @Test
    void repeatingARequestReturnsTheOriginalTransactionAndMovesMoneyOnce() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        String key = key();

        TransferService.Result first = transfers.transfer(key, a, b, 100, "USD", "rent");
        TransferService.Result second = transfers.transfer(key, a, b, 100, "USD", "rent");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        String key = key();
        transfers.transfer(key, a, b, 100, "USD", null);

        assertApiError("IDEMPOTENCY_KEY_REUSED", () -> transfers.transfer(key, a, b, 999, "USD", null));
        // A key is global: reusing it for a different operation is also refused.
        assertApiError("IDEMPOTENCY_KEY_REUSED", () -> transfers.deposit(key, a, 100, "USD", null));
        assertThat(balance(a)).isEqualTo(900);
    }

    @Test
    void failedRequestDoesNotBurnTheKey() {
        UUID a = newAccount("USD");
        UUID b = newAccount("USD");
        String key = key();

        assertApiError("INSUFFICIENT_FUNDS", () -> transfers.transfer(key, a, b, 100, "USD", null));
        deposit(a, 100, "USD");
        TransferService.Result retry = transfers.transfer(key, a, b, 100, "USD", null);

        assertThat(retry.replayed()).isFalse();
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void concurrentDuplicatesProduceExactlyOneTransaction() throws Exception {
        UUID a = fundedAccount("USD", 10_000);
        UUID b = newAccount("USD");
        String key = key();
        int threads = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    ids.add(transfers.transfer(key, a, b, 250, "USD", null).transaction().id());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).hasSize(1);
        assertThat(balance(a)).isEqualTo(10_000 - 250);
        assertThat(balance(b)).isEqualTo(250);
    }

    @Test
    void replayAfterReversalStillReturnsTheOriginalAndPostsNothing() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        String key = key();
        TransferService.Result first = transfers.transfer(key, a, b, 100, "USD", null);
        reversals.reverse(first.transaction().id(), null);

        TransferService.Result replay = transfers.transfer(key, a, b, 100, "USD", null);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(balance(a)).isEqualTo(1_000);   // NOT re-sent after the reversal
    }

    @Test
    void blankAndMissingDescriptionAreTheSameRequestAndCurrencyIsCaseInsensitive() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        String key = key();
        TransferService.Result first = transfers.transfer(key, a, b, 100, "usd", "  ");
        TransferService.Result second = transfers.transfer(key, a, b, 100, "USD", null);
        assertThat(second.replayed()).isTrue();
        assertThat(first.transaction().sourceCurrency()).isEqualTo("USD");
    }

    @Test
    void rejectsMalformedKeys() {
        UUID a = fundedAccount("USD", 1_000);
        UUID b = newAccount("USD");
        assertApiError("INVALID_IDEMPOTENCY_KEY", () -> transfers.transfer("", a, b, 1, "USD", null));
        assertApiError("INVALID_IDEMPOTENCY_KEY", () -> transfers.transfer("x".repeat(101), a, b, 1, "USD", null));
        assertApiError("INVALID_IDEMPOTENCY_KEY", () -> transfers.transfer("has space", a, b, 1, "USD", null));
    }
}
