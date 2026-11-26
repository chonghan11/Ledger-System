package com.ledger.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** Runs the previous month's reconciliation automatically at month start (UTC). */
@Component
@ConditionalOnProperty(prefix = "ledger.reconciliation", name = "auto-run", havingValue = "true")
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliation;
    private final Clock clock;

    public ReconciliationScheduler(ReconciliationService reconciliation, Clock clock) {
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    @Scheduled(cron = "${ledger.reconciliation.cron:0 5 0 1 * *}", zone = "UTC")
    public void runPreviousMonth() {
        YearMonth previous = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
        try {
            ReconciliationService.Run run = reconciliation.run(previous);
            if ("BALANCED".equals(run.status())) {
                log.info("month-end reconciliation {} BALANCED ({} accounts)", previous, run.accountsChecked());
            } else {
                log.error("month-end reconciliation {} found {} discrepancies (run {})",
                        previous, run.discrepancies().size(), run.id());
            }
        } catch (RuntimeException e) {
            log.error("month-end reconciliation {} failed", previous, e);
        }
    }
}
