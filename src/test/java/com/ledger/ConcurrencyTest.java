package com.ledger;

import com.ledger.error.ApiException;
import com.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many real, simultaneous DB transactions at the same accounts. A start latch releases all threads at
 * once to maximise contention. These would fail without row locks (lost updates / overdrafts) or without
 * ordered locking (deadlocks).
 */
class ConcurrencyTest extends IntegrationTest {

    @Test
    void concurrentTransfersFromOneAccountNeverOverdraw() throws Exception {
        UUID source = fundedAccount("USD", 500);
        UUID target = newAccount("USD");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(100, i -> {
            try {
                transfer(source, target, 10, "USD");
                succeeded.incrementAndGet();
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS");
                rejected.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(50);   // exactly 500 / 10
        assertThat(rejected.get()).isEqualTo(50);
        assertThat(balance(source)).isZero();
        assertThat(balance(target)).isEqualTo(500);
        assertLedgerHealthy();
    }

    @Test
    void concurrentCreditsToOneAccountLoseNoUpdates() throws Exception {
        UUID hot = newAccount("USD");
        List<UUID> senders = new ArrayList<>();
        for (int i = 0; i < 20; i++) senders.add(fundedAccount("USD", 1_000));

        runConcurrently(200, i -> transfer(senders.get(i % senders.size()), hot, 7, "USD"));

        assertThat(balance(hot)).isEqualTo(200 * 7);
        assertThat(senders.stream().mapToLong(this::balance).sum()).isEqualTo(20 * 1_000 - 200 * 7);
        assertLedgerHealthy();
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        UUID a = fundedAccount("USD", 10_000);
        UUID b = fundedAccount("USD", 10_000);

        // Half the threads go A->B, half B->A. Without a global lock order this deadlocks quickly.
        runConcurrently(200, i -> {
            if (i % 2 == 0) transfer(a, b, 3, "USD");
            else transfer(b, a, 3, "USD");
        });

        assertThat(balance(a) + balance(b)).isEqualTo(20_000);   // money conserved
        assertThat(balance(a)).isEqualTo(10_000);                // 100 each way
        assertLedgerHealthy();
    }

    @Test
    void concurrentCrossCurrencyTransfersStayBalanced() throws Exception {
        UUID usd = fundedAccount("USD", 100_000);
        UUID sgd = fundedAccount("SGD", 100_000);

        runConcurrently(60, i -> {
            if (i % 2 == 0) transfer(usd, sgd, 100, "USD");
            else transfer(sgd, usd, 100, "SGD");
        });

        assertLedgerHealthy();   // every currency still sums to zero, FX accounts included
    }

    // ------------------------------------------------------------------------------------------

    interface Task { void run(int i) throws Exception; }

    private static void runConcurrently(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 25));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int n = i;
                Callable<Object> c = () -> {
                    start.await();
                    task.run(n);
                    return null;
                };
                futures.add(pool.submit(c));
            }
            start.countDown();
            for (Future<Object> f : futures) f.get();   // rethrows any unexpected failure (e.g. deadlock)
        } finally {
            pool.shutdownNow();
        }
    }
}
