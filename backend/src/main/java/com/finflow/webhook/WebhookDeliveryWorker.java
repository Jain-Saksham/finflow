package com.finflow.webhook;

import com.finflow.common.web.RequestIdFilter;
import com.finflow.webhook.WebhookDeliveryStore.Claimed;
import com.finflow.webhook.WebhookDeliveryStore.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers due webhooks. Claiming uses FOR UPDATE SKIP LOCKED plus a lease, so several instances
 * can run this worker and a crashed worker's deliveries are picked up again after the lease.
 * Delivery is at-least-once: the receiver must deduplicate on {@code FinFlow-Event-Id}.
 */
@Component
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookDeliveryStore store;
    private final WebhookEndpointRepository endpoints;
    private final WebhookProperties properties;
    private final MeterRegistry registry;
    private final HttpClient http;
    private final AtomicLong due = new AtomicLong();

    public WebhookDeliveryWorker(WebhookDeliveryStore store, WebhookEndpointRepository endpoints,
                                 WebhookProperties properties, MeterRegistry registry) {
        this.store = store;
        this.endpoints = endpoints;
        this.properties = properties;
        this.registry = registry;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER) // a redirect could point anywhere (SSRF)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        Gauge.builder("finflow.webhook.deliveries.due", due, AtomicLong::get)
                .description("Webhook deliveries pending, retrying or in progress").register(registry);
    }

    @Scheduled(fixedDelayString = "${finflow.webhooks.poll-interval}")
    public void poll() {
        if (properties.workerEnabled()) {
            // Keep draining while full batches come back, so a backlog clears at full speed.
            while (runOnce() == properties.batchSize()) {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Claims one batch and delivers it concurrently on virtual threads (bounded by the batch size), so
     * one slow receiver does not hold up the others. Returns how many deliveries were attempted.
     */
    public int runOnce() {
        try {
            List<Claimed> batch = store.claimDue(properties.batchSize(), properties.lease());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                batch.forEach(delivery -> executor.submit(() -> deliver(delivery)));
            } // close() waits for every delivery in the batch
            due.set(store.countDue());
            return batch.size();
        } catch (RuntimeException e) {
            log.warn("Webhook worker run failed: {}", e.getMessage());
            return 0;
        }
    }

    private void deliver(Claimed delivery) {
        if (delivery.correlationId() != null) {
            MDC.put(RequestIdFilter.MDC_KEY, delivery.correlationId());
        }
        try {
            WebhookEndpoint endpoint = endpoints.findById(delivery.endpointId()).orElse(null);
            if (endpoint == null || !endpoint.isEnabled()) {
                store.markFailed(delivery.id(), "Endpoint disabled");
                return;
            }
            Instant started = Instant.now();
            long timestamp = started.getEpochSecond();
            Integer status = null;
            String body = null;
            String error = null;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.getUrl()))
                        .timeout(properties.requestTimeout())
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FinFlow-Webhooks/1.0")
                        .header("FinFlow-Event-Id", delivery.eventId().toString())
                        .header("FinFlow-Event-Type", delivery.eventType())
                        .header("FinFlow-Delivery-Id", delivery.id().toString())
                        .header(WebhookSigner.HEADER, WebhookSigner.sign(endpoint.getSecret(), timestamp, delivery.payload()))
                        .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                body = response.body();
                if (status < 200 || status >= 300) {
                    error = "HTTP " + status;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Interrupted";
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            boolean success = error == null;
            int duration = (int) Duration.between(started, Instant.now()).toMillis();
            Status next = store.recordAttempt(delivery.id(), success, status, body, error, duration, started,
                    properties.retrySchedule());
            registry.counter("finflow.webhook.attempts", "outcome", success ? "succeeded" : "failed").increment();
            if (next == Status.RETRYING) {
                registry.counter("finflow.webhook.retries.scheduled").increment();
            } else if (next == Status.FAILED) {
                registry.counter("finflow.webhook.deliveries.failed").increment();
                log.warn("Webhook delivery {} for event {} failed permanently: {}", delivery.id(), delivery.eventId(), error);
            }
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }
}
