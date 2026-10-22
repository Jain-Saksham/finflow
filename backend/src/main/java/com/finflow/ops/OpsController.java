package com.finflow.ops;

import com.finflow.common.error.ApiException;
import com.finflow.common.web.PageResponse;
import com.finflow.events.DeadLetterStore;
import com.finflow.ledger.LedgerService;
import com.finflow.outbox.OutboxStore;
import com.finflow.reconciliation.ReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only endpoints (X-Ops-Token; disabled unless FINFLOW_OPS_TOKEN is set). Not in the
 * public OpenAPI document. See docs/PROJECT_GUIDE.md for the replay and correction procedures.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final DeadLetterStore deadLetters;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxStore outbox;
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final ReconciliationService reconciliation;

    public OpsController(DeadLetterStore deadLetters, KafkaTemplate<String, String> kafka, OutboxStore outbox,
                         JdbcTemplate jdbc, LedgerService ledger, ReconciliationService reconciliation) {
        this.deadLetters = deadLetters;
        this.kafka = kafka;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/dead-letters")
    public PageResponse<DeadLetterStore.DeadLetter> deadLetters(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return deadLetters.list(page, size);
    }

    /**
     * Re-publishes a dead letter to its original topic. Every consumer group subscribed to that topic
     * sees it again; that is safe because all consumers are idempotent on the event id.
     */
    @PostMapping("/dead-letters/{id}/replay")
    public Map<String, Object> replay(@PathVariable long id) throws Exception {
        DeadLetterStore.DeadLetter letter = deadLetters.find(id).orElseThrow(() -> ApiException.notFound("Dead letter", id));
        String topic = letter.originalTopic() != null ? letter.originalTopic() : letter.topic().replaceAll("\\.dlt$", "");
        var record = new ProducerRecord<>(topic, letter.key(), letter.payload());
        record.headers().add("finflow-replayed-dead-letter", String.valueOf(id).getBytes(StandardCharsets.UTF_8));
        var result = kafka.send(record).get(10, TimeUnit.SECONDS);
        deadLetters.markReplayed(id);
        log.warn("Replayed dead letter {} to {}-{}@{}", id, topic, result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
        return Map.of("deadLetterId", id, "topic", topic, "partition", result.getRecordMetadata().partition(),
                "offset", result.getRecordMetadata().offset());
    }

    @GetMapping("/outbox")
    public OutboxStore.Stats outboxStats() {
        return outbox.stats();
    }

    /** Re-queues outbox events that exhausted their publish attempts (e.g. after a long Kafka outage). */
    @PostMapping("/outbox/retry-failed")
    public Map<String, Object> retryFailedOutbox() {
        int n = jdbc.update("update outbox_events set status = 'PENDING', publish_attempts = 0, next_attempt_at = now() "
                + "where status = 'FAILED'");
        return Map.of("requeued", n);
    }

    public record ReversalRequest(@NotBlank @Size(max = 200) String reason) {
    }

    /** The only way to correct the ledger: post a mirror-image journal. */
    @PostMapping("/merchants/{merchantId}/ledger/journals/{journalId}/reverse")
    public Map<String, Object> reverse(@PathVariable UUID merchantId, @PathVariable UUID journalId,
                                       @Valid @RequestBody ReversalRequest request) {
        UUID reversal = ledger.reverse(merchantId, journalId, request.reason());
        return Map.of("reversedJournalId", journalId, "reversalJournalId", reversal);
    }

    @PostMapping("/merchants/{merchantId}/reconciliation-runs")
    public Map<String, Object> reconcile(@PathVariable UUID merchantId) {
        return Map.of("runId", reconciliation.run(merchantId, ReconciliationService.Trigger.OPS));
    }
}
