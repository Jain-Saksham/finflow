package com.finflow.reconciliation;

import com.finflow.common.error.ApiException;
import com.finflow.common.web.PageResponse;
import com.finflow.common.web.Paging;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation/runs")
@Tag(name = "Reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;
    private final JdbcTemplate jdbc;
    private final ReconciliationProperties properties;

    public ReconciliationController(ReconciliationService service, JdbcTemplate jdbc, ReconciliationProperties properties) {
        this.service = service;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record RunResponse(UUID id, String trigger, String status, Integer providerTransactions,
                              Integer internalPayments, Integer matchedPayments, Integer inFlightSkipped,
                              long staleAfterSeconds, Integer findingsTotal,
                              Integer autoResolved, Integer openFindings, String error, Instant startedAt,
                              Instant finishedAt) {
    }

    public record FindingResponse(long id, String type, String severity, boolean recoverable, String resolution,
                                  UUID paymentId, UUID refundId, String providerReference, String expected,
                                  String actual, String detail, String resolutionDetail, Instant createdAt) {
    }

    public record RunReport(RunResponse run, List<FindingResponse> findings) {
    }

    private RowMapper<RunResponse> runMapper() {
        return (rs, i) -> new RunResponse(rs.getObject("id", UUID.class),
            rs.getString("trigger_type"), rs.getString("status"), (Integer) rs.getObject("provider_transactions"),
            (Integer) rs.getObject("internal_payments"), (Integer) rs.getObject("matched_payments"),
            (Integer) rs.getObject("in_flight_skipped"), properties.staleAfter().toSeconds(),
            (Integer) rs.getObject("findings_total"), (Integer) rs.getObject("auto_resolved"),
            (Integer) rs.getObject("open_findings"), rs.getString("error"), rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    }

    private static final RowMapper<FindingResponse> FINDING = (rs, i) -> new FindingResponse(rs.getLong("id"),
            rs.getString("type"), rs.getString("severity"), rs.getBoolean("recoverable"), rs.getString("resolution"),
            rs.getObject("payment_id", UUID.class), rs.getObject("refund_id", UUID.class),
            rs.getString("provider_reference"), rs.getString("expected"), rs.getString("actual"),
            rs.getString("detail"), rs.getString("resolution_detail"), rs.getTimestamp("created_at").toInstant());

    @PostMapping
    @Operation(summary = "Run reconciliation now against the simulated provider's records")
    public RunReport run(@AuthenticationPrincipal MerchantPrincipal merchant) {
        UUID runId = service.run(merchant.merchantId(), ReconciliationService.Trigger.MANUAL);
        return report(merchant, runId);
    }

    @GetMapping
    @Operation(summary = "List reconciliation runs, newest first")
    public PageResponse<RunResponse> runs(@AuthenticationPrincipal MerchantPrincipal merchant,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = Paging.DEFAULT_SIZE) @Min(1) @Max(Paging.MAX_SIZE) int size) {
        Long total = jdbc.queryForObject("select count(*) from reconciliation_runs where merchant_id = ?", Long.class,
                merchant.merchantId());
        List<RunResponse> content = jdbc.query("select * from reconciliation_runs where merchant_id = ? "
                + "order by started_at desc limit ? offset ?", runMapper(), merchant.merchantId(), size, (long) page * size);
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, page, size, t, (int) Math.ceil(t / (double) size));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "A run with all of its findings (the reconciliation report)")
    public RunReport report(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID runId) {
        RunResponse run = jdbc.query("select * from reconciliation_runs where id = ? and merchant_id = ?", runMapper(), runId,
                merchant.merchantId()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Reconciliation run", runId));
        return new RunReport(run, jdbc.query("select * from reconciliation_findings where run_id = ? order by id", FINDING,
                runId));
    }

    @GetMapping(value = "/{runId}/report.csv", produces = "text/csv")
    @Operation(summary = "Download the findings of a run as CSV")
    public ResponseEntity<String> csv(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable UUID runId) {
        RunReport report = report(merchant, runId);
        StringBuilder csv = new StringBuilder("type,severity,recoverable,resolution,payment_id,refund_id,"
                + "provider_reference,expected,actual,detail,resolution_detail\n");
        for (FindingResponse f : report.findings()) {
            csv.append(String.join(",", f.type(), f.severity(), String.valueOf(f.recoverable()), f.resolution(),
                    str(f.paymentId()), str(f.refundId()), q(f.providerReference()), q(f.expected()), q(f.actual()),
                    q(f.detail()), q(f.resolutionDetail()))).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reconciliation-" + runId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** RFC 4180 quoting; also neutralizes spreadsheet formula injection. */
    private static String q(String s) {
        if (s == null) {
            return "";
        }
        String safe = s.matches("^[=+\\-@].*") ? "'" + s : s;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
