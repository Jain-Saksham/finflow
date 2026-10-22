package com.finflow.events;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.common.web.RequestIdFilter;
import com.finflow.payment.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Asynchronous recovery: when a payment times out, ask the provider what happened (after a short
 * delay) and apply the answer, so most TIMED_OUT payments resolve without anyone pressing "sync".
 *
 * <p>Idempotent by construction: the payment state machine only allows resolving a TIMED_OUT
 * payment once, so a duplicate event (or a manual sync that got there first) is a no-op. A provider
 * outage is rethrown so the error handler retries with backoff and finally dead-letters it.
 */
@Component
public class TimeoutRecoveryListener {

    public static final String GROUP = "finflow-timeout-recovery";
    private static final Logger log = LoggerFactory.getLogger(TimeoutRecoveryListener.class);

    private final EventParser parser;
    private final PaymentService payments;
    private final MeterRegistry registry;
    private final Duration delay;

    public TimeoutRecoveryListener(EventParser parser, PaymentService payments, MeterRegistry registry,
                                   @Value("${finflow.recovery.auto-sync-delay}") Duration delay) {
        this.parser = parser;
        this.payments = payments;
        this.registry = registry;
        this.delay = delay;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${finflow.kafka.topic}",
            autoStartup = "${finflow.recovery.auto-sync-enabled}")
    public void onEvent(ConsumerRecord<String, String> record) throws InterruptedException {
        PaymentEventMessage event = parser.parse(record.value());
        if (!"payment.timed_out".equals(event.type())) {
            return;
        }
        // Give the provider a moment; blocks only this consumer group's partition, not the API.
        Duration remaining = Duration.between(Instant.now(), event.createdAt().plus(delay));
        if (!remaining.isNegative()) {
            Thread.sleep(remaining.toMillis());
        }
        if (event.correlationId() != null) {
            MDC.put(RequestIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            var payment = payments.resolveFromProvider(event.merchantId(), event.data().paymentId(), Duration.ZERO);
            registry.counter("finflow.recovery.timeouts", "result", "resolved").increment();
            log.info("Auto-resolved timed-out payment {} -> {}", payment.getId(), payment.getStatus());
        } catch (ApiException e) {
            if (e.code() == ErrorCode.INVALID_STATE_TRANSITION || e.code() == ErrorCode.NOT_FOUND) {
                registry.counter("finflow.recovery.timeouts", "result", "already_resolved").increment();
                return; // duplicate event, or someone synced it first
            }
            throw e; // provider unavailable etc.: retry, then dead-letter
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }
}
