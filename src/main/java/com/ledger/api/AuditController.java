package com.ledger.api;

import com.ledger.audit.IntegrityCheckService;
import com.ledger.audit.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.UUID;

/** Operational endpoints. In production these would sit behind admin authentication. */
@RestController
@RequestMapping("/admin")
public class AuditController {

    private final IntegrityCheckService integrity;
    private final ReconciliationService reconciliation;

    public AuditController(IntegrityCheckService integrity, ReconciliationService reconciliation) {
        this.integrity = integrity;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/integrity-check")
    public IntegrityCheckService.Report integrityCheck() {
        return integrity.run();
    }

    /** Body: {"period": "2026-10"}. The month must have ended. */
    @PostMapping("/reconciliations")
    public ResponseEntity<ReconciliationService.Run> reconcile(@Valid @RequestBody Requests.Reconciliation req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reconciliation.run(YearMonth.parse(req.period())));
    }

    @GetMapping("/reconciliations/{id}")
    public ReconciliationService.Run get(@PathVariable UUID id) {
        return reconciliation.load(id);
    }
}
