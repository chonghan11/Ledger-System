package com.ledger.support;

import com.ledger.account.AccountService;
import com.ledger.audit.IntegrityCheckService;
import com.ledger.error.ApiException;
import com.ledger.posting.LedgerTransaction;
import com.ledger.transaction.ReversalService;
import com.ledger.transaction.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Base for integration tests: one real Postgres container shared by all test classes (started once per JVM),
 * the full Spring context, and a controllable clock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.reconciliation.auto-run=false",
        "ledger.hold-period=PT10M",
        "spring.datasource.hikari.maximum-pool-size=30"
})
@Import(IntegrationTest.TestClockConfig.class)
public abstract class IntegrationTest {

    public static final Instant DEFAULT_NOW = Instant.parse("2026-06-15T10:00:00Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(DEFAULT_NOW);
        }
    }

    @Autowired protected MutableClock clock;
    @Autowired protected AccountService accounts;
    @Autowired protected TransferService transfers;
    @Autowired protected ReversalService reversals;
    @Autowired protected IntegrityCheckService integrity;
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void resetClock() {
        clock.set(DEFAULT_NOW);
    }

    // ---- helpers -------------------------------------------------------------------------

    protected UUID newAccount(String currency) {
        return accounts.create("test-" + UUID.randomUUID().toString().substring(0, 8), currency).id();
    }

    protected UUID fundedAccount(String currency, long amount) {
        UUID id = newAccount(currency);
        deposit(id, amount, currency);
        return id;
    }

    protected LedgerTransaction deposit(UUID account, long amount, String currency) {
        return transfers.deposit(key(), account, amount, currency, null).transaction();
    }

    protected LedgerTransaction transfer(UUID from, UUID to, long amount, String currency) {
        return transfers.transfer(key(), from, to, amount, currency, null).transaction();
    }

    protected long balance(UUID account) {
        return accounts.get(account).balance();
    }

    protected long available(UUID account) {
        return accounts.get(account).availableBalance();
    }

    protected static String key() {
        return "test-" + UUID.randomUUID();
    }

    protected static ApiException assertApiError(String expectedCode, Executable call) {
        ApiException e = assertThrows(ApiException.class, call);
        assertThat(e.code()).isEqualTo(expectedCode);
        return e;
    }

    protected void assertLedgerHealthy() {
        IntegrityCheckService.Report report = integrity.run();
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.healthy()).isTrue();
    }
}
