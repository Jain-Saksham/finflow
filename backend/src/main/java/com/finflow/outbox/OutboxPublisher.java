package com.finflow.outbox;

import com.finflow.outbox.OutboxStore.PendingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays outbox rows to Kafka. Delivery is <b>at-least-once</b>: if the process dies after Kafka
 * acknowledged a record but before the row is marked PUBLISHED, the row is sent again on the next
 * poll. Consumers therefore deduplicate on the event id.
 *
 * <p>A batch is sent pipelined (all records in flight at once, then all acknowledgements awaited)
 * and marked published in one statement; sending records one at a time capped throughput at a few
 * hundred events per second in load tests. Records are keyed by payment id, so a payment's events
 * share a partition and normally arrive in order. Ordering is <em>not</em> guaranteed when a send
 * fails and is retried later; consumers must not depend on it (each event carries the full payment
 * snapshot and its timestamp). A Postgres advisory lock keeps a single active publisher across
 * instances.
 */
@Component
public class OutboxPublisher {

    public static final String HEADER_EVENT_ID = "finflow-event-id";
    public static final String HEADER_EVENT_TYPE = "finflow-event-type";
    public static final String HEADER_REQUEST_ID = "x-request-id";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final KafkaTopicsProperties topics;
    private final TransactionTemplate tx;
    private final Counter published;
    private final Counter failures;
    private final AtomicReference<OutboxStore.Stats> lastStats = new AtomicReference<>(new OutboxStore.Stats(0, 0, null));

    public OutboxPublisher(OutboxStore store, KafkaTemplate<String, String> kafka, OutboxProperties properties,
                           KafkaTopicsProperties topics, PlatformTransactionManager transactionManager,
                           MeterRegistry registry) {
        this.store = store;
        this.kafka = kafka;
        this.properties = properties;
        this.topics = topics;
        this.tx = new TransactionTemplate(transactionManager);
        this.published = Counter.builder("finflow.outbox.published").description("Outbox events sent to Kafka")
                .register(registry);
        this.failures = Counter.builder("finflow.outbox.publish.failures").description("Failed Kafka sends")
                .register(registry);
        Gauge.builder("finflow.outbox.pending", lastStats, s -> s.get().pending())
                .description("Outbox events not yet published").register(registry);
        Gauge.builder("finflow.outbox.failed", lastStats, s -> s.get().failed())
                .description("Outbox events that exhausted their publish attempts").register(registry);
        Gauge.builder("finflow.outbox.lag.seconds", lastStats, s -> {
                    Instant oldest = s.get().oldestPendingCreatedAt();
                    return oldest == null ? 0 : Duration.between(oldest, Instant.now()).toMillis() / 1000.0;
                })
                .description("Age of the oldest unpublished outbox event").register(registry);
    }

    @Scheduled(fixedDelayString = "${finflow.outbox.poll-interval}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            int sent;
            do {
                sent = publishBatch();
            } while (sent == properties.batchSize());
            lastStats.set(store.stats());
        } catch (RuntimeException e) {
            log.warn("Outbox poll failed: {}", e.getMessage());
        }
    }

    /** @return number of events processed (equals batch size when more may be waiting) */
    public int publishBatch() {
        return Objects.requireNonNull(tx.execute(status -> {
            if (!store.tryAcquirePublisherLock()) {
                return 0; // another instance is publishing
            }
            List<PendingEvent> batch = store.lockNextBatch(properties.batchSize());
            Map<PendingEvent, CompletableFuture<?>> inFlight = new LinkedHashMap<>();
            for (PendingEvent event : batch) {
                try {
                    inFlight.put(event, send(event));
                } catch (RuntimeException e) {
                    // send() itself failed (e.g. no broker metadata within max.block.ms): the broker is
                    // unreachable, so do not spend the rest of the batch timing out one by one.
                    recordFailure(event, e);
                    break;
                }
            }
            long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
            List<UUID> acknowledged = new ArrayList<>();
            for (var entry : inFlight.entrySet()) {
                try {
                    entry.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    acknowledged.add(entry.getKey().id());
                } catch (Exception e) {
                    recordFailure(entry.getKey(), e);
                }
            }
            store.markPublished(acknowledged);
            published.increment(acknowledged.size());
            return batch.size();
        }));
    }

    private void recordFailure(PendingEvent event, Exception e) {
        failures.increment();
        int attempts = event.attempts() + 1;
        boolean giveUp = attempts >= properties.maxAttempts();
        Duration backoff = Duration.ofMillis(Math.min(60_000, 250L << Math.min(attempts, 8)));
        store.markAttemptFailed(event.id(), attempts, giveUp, backoff, rootMessage(e));
        log.warn("Outbox publish of {} failed (attempt {}{}): {}", event.id(), attempts, giveUp ? ", giving up" : "",
                rootMessage(e));
    }

    private CompletableFuture<?> send(PendingEvent event) {
        var record = new ProducerRecord<String, String>(topics.topic(), event.aggregateId().toString(), event.payload());
        record.headers().add(HEADER_EVENT_ID, event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(HEADER_EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        if (event.correlationId() != null) {
            record.headers().add(HEADER_REQUEST_ID, event.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        return kafka.send(record);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
