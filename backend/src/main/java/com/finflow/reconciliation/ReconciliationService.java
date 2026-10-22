package com.finflow.reconciliation;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.payment.PaymentService;
import com.finflow.payment.PaymentStatus;
import com.finflow.provider.PaymentProvider;
import com.finflow.provider.ProviderTransaction;
import com.finflow.reconciliation.ReconciliationMatcher.InternalPayment;
import com.finflow.reconciliation.ReconciliationMatcher.InternalRefund;
import com.finflow.reconciliation.ReconciliationMatcher.LedgerNet;
import com.finflow.refund.RefundService;
import com.finflow.refund.RefundStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Compares FinFlow with the provider's independent records, applies the safe automatic repairs
 * (each one an existing idempotent operation guarded by the state machine), and writes an
 * append-only report: one run row plus one finding row per discrepancy, recorded after the
 * repair was attempted so the finding states what actually happened.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public enum Trigger { MANUAL, SCHEDULED, OPS }

    private final JdbcTemplate jdbc;
    private final PaymentProvider provider;
    private final PaymentService payments;
    private final RefundService refunds;
    private final ReconciliationProperties properties;
    private final MeterRegistry registry;

    public ReconciliationService(JdbcTemplate jdbc, PaymentProvider provider, PaymentService payments,
                                 RefundService refunds, ReconciliationProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.payments = payments;
        this.refunds = refunds;
        this.properties = properties;
        this.registry = registry;
    }

    public UUID run(UUID merchantId, Trigger trigger) {
        UUID runId = UUID.randomUUID();
        // A run that died mid-way would block the merchant forever; expire it.
        jdbc.update("update reconciliation_runs set status = 'FAILED', error = 'abandoned', finished_at = now() "
                + "where merchant_id = ? and status = 'RUNNING' and started_at < now() - interval '15 minutes'", merchantId);
        try {
            jdbc.update("insert into reconciliation_runs (id, merchant_id, trigger_type, status, started_at) "
                    + "values (?, ?, ?, 'RUNNING', now())", runId, merchantId, trigger.name());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.RECONCILIATION_IN_PROGRESS, "A reconciliation run is already in progress");
        }
        try {
            List<ProviderTransaction> providerTxs = provider.transactions(merchantId);
            List<InternalPayment> internal = loadPayments(merchantId);
            var result = ReconciliationMatcher.match(providerTxs, internal, loadRefunds(merchantId),
                    loadLedger(merchantId), Instant.now(), properties.staleAfter());

            int autoResolved = 0;
            int open = 0;
            for (Discrepancy d : result.discrepancies()) {
                String resolution = "OPEN";
                String resolutionDetail = null;
                if (d.recoverable()) {
                    try {
                        resolutionDetail = repair(merchantId, d);
                        resolution = "AUTO_RESOLVED";
                        autoResolved++;
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.INVALID_STATE_TRANSITION) {
                            // Another path (timeout-recovery consumer, manual sync) repaired it first.
                            resolutionDetail = "Already resolved concurrently: " + e.getMessage();
                            resolution = "AUTO_RESOLVED";
                            autoResolved++;
                        } else {
                            resolution = "RESOLUTION_FAILED";
                            resolutionDetail = e.getMessage();
                            open++;
                        }
                    } catch (RuntimeException e) {
                        resolution = "RESOLUTION_FAILED";
                        resolutionDetail = e.getMessage();
                        open++;
                    }
                } else {
                    open++;
                }
                insertFinding(runId, merchantId, d, resolution, resolutionDetail);
                registry.counter("finflow.reconciliation.findings", "type", d.type().name(), "resolution", resolution)
                        .increment();
            }
            jdbc.update("""
                    update reconciliation_runs set status = 'COMPLETED', provider_transactions = ?, internal_payments = ?,
                        matched_payments = ?, in_flight_skipped = ?, findings_total = ?, auto_resolved = ?,
                        open_findings = ?, finished_at = now()
                    where id = ?""",
                    providerTxs.size(), internal.size(), result.matchedPayments(), result.skippedInFlight(),
                    result.discrepancies().size(), autoResolved, open, runId);
            registry.counter("finflow.reconciliation.runs", "status", "completed", "trigger", trigger.name()).increment();
            log.info("Reconciliation {} for merchant {}: {} findings ({} auto-resolved, {} open)", runId, merchantId,
                    result.discrepancies().size(), autoResolved, open);
            return runId;
        } catch (RuntimeException e) {
            jdbc.update("update reconciliation_runs set status = 'FAILED', error = ?, finished_at = now() where id = ?",
                    String.valueOf(e.getMessage()), runId);
            registry.counter("finflow.reconciliation.runs", "status", "failed", "trigger", trigger.name()).increment();
            throw e;
        }
    }

    private String repair(UUID merchantId, Discrepancy d) {
        return switch (d.action()) {
            case RESOLVE_FROM_PROVIDER -> "Payment is now "
                    + payments.resolveFromProvider(merchantId, d.paymentId(), properties.staleAfter()).getStatus();
            case APPLY_CAPTURE -> "Payment is now " + payments.capture(merchantId, d.paymentId()).getStatus();
            case CONFIRM_REFUND -> "Refund is now "
                    + refunds.confirmFromProvider(merchantId, d.refundId(), d.providerReference()).getStatus();
            case FAIL_REFUND -> "Refund is now " + refunds.failNotFoundAtProvider(merchantId, d.refundId()).getStatus();
            case NONE -> throw new IllegalStateException("not recoverable");
        };
    }

    /** Scheduled sweep over merchants that have payments. Each merchant's run is independent. */
    @Scheduled(fixedDelayString = "${finflow.reconciliation.interval}", initialDelayString = "${finflow.reconciliation.interval}")
    public void scheduledRun() {
        if (!properties.scheduleEnabled()) {
            return;
        }
        for (UUID merchantId : jdbc.queryForList("select distinct merchant_id from payments", UUID.class)) {
            try {
                run(merchantId, Trigger.SCHEDULED);
            } catch (ApiException e) {
                log.debug("Skipping merchant {}: {}", merchantId, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Scheduled reconciliation failed for merchant {}", merchantId, e);
            }
        }
    }

    private void insertFinding(UUID runId, UUID merchantId, Discrepancy d, String resolution, String resolutionDetail) {
        jdbc.update("""
                insert into reconciliation_findings (run_id, merchant_id, type, severity, recoverable, resolution,
                    payment_id, refund_id, provider_reference, expected, actual, detail, resolution_detail, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                runId, merchantId, d.type().name(), d.severity().name(), d.recoverable(), resolution, d.paymentId(),
                d.refundId(), d.providerReference(), cut(d.expected(), 255), cut(d.actual(), 255), cut(d.detail(), 500),
                cut(resolutionDetail, 500), Timestamp.from(Instant.now()));
    }

    private List<InternalPayment> loadPayments(UUID merchantId) {
        return jdbc.query("""
                select id, status, amount_minor, captured_amount_minor, refunded_amount_minor, currency,
                       provider_reference, updated_at
                from payments where merchant_id = ?""",
                (rs, i) -> new InternalPayment(rs.getObject("id", UUID.class), PaymentStatus.valueOf(rs.getString("status")),
                        rs.getLong("amount_minor"), rs.getLong("captured_amount_minor"), rs.getLong("refunded_amount_minor"),
                        rs.getString("currency"), rs.getString("provider_reference"),
                        rs.getTimestamp("updated_at").toInstant()),
                merchantId);
    }

    private List<InternalRefund> loadRefunds(UUID merchantId) {
        return jdbc.query("select id, payment_id, status, amount_minor, updated_at from refunds where merchant_id = ?",
                (rs, i) -> new InternalRefund(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                        RefundStatus.valueOf(rs.getString("status")), rs.getLong("amount_minor"),
                        rs.getTimestamp("updated_at").toInstant()),
                merchantId);
    }

    private List<LedgerNet> loadLedger(UUID merchantId) {
        return jdbc.query("""
                select payment_id, sum(case direction when 'DEBIT' then amount_minor else -amount_minor end) as net
                from ledger_entries where merchant_id = ? and account = 'PROVIDER_RECEIVABLE'
                group by payment_id""",
                (rs, i) -> new LedgerNet(rs.getObject("payment_id", UUID.class), rs.getLong("net")), merchantId);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
