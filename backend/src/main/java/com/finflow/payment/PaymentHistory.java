package com.finflow.payment;

import com.finflow.common.web.RequestIdFilter;
import com.finflow.events.PaymentEventMessage;
import com.finflow.events.PaymentStateChanged;
import com.finflow.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records a payment state change in the caller's transaction: the history row, the outbox event
 * that will be published to Kafka, and an in-process notification (cache eviction). Metrics are
 * only counted after commit so rolled-back changes are never reported.
 */
@Component
public class PaymentHistory {

    private static final Logger log = LoggerFactory.getLogger(PaymentHistory.class);

    private final PaymentEventRepository events;
    private final OutboxWriter outbox;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry registry;

    public PaymentHistory(PaymentEventRepository events, OutboxWriter outbox, ApplicationEventPublisher publisher,
                          MeterRegistry registry) {
        this.events = events;
        this.outbox = outbox;
        this.publisher = publisher;
        this.registry = registry;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Payment payment, PaymentEvent event) {
        events.save(event);
        outbox.append(toMessage(payment, event));
        publisher.publishEvent(new PaymentStateChanged(payment.getMerchantId(), payment.getId()));
        String type = event.getType().name();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                registry.counter("finflow.payment.events", "type", type).increment();
            }
        });
        log.info("payment={} event={} {} -> {}", event.getPaymentId(), event.getType(), event.getFromStatus(),
                event.getToStatus());
    }

    private static PaymentEventMessage toMessage(Payment p, PaymentEvent e) {
        var data = new PaymentEventMessage.Data(p.getId(), p.getOrderId(), p.getStatus().name(),
                e.getFromStatus() == null ? null : e.getFromStatus().name(), p.getAmountMinor(), p.getCurrency(),
                p.getCapturedAmountMinor(), p.getRefundedAmountMinor(), e.getAmountMinor(), p.getCaptureMode().name(),
                p.getProviderReference(), p.getFailureCode(), p.getFailureMessage(), e.getDetail());
        return new PaymentEventMessage(UUID.randomUUID(), "payment." + e.getType().name().toLowerCase(),
                PaymentEventMessage.API_VERSION, e.getCreatedAt(), p.getMerchantId(), MDC.get(RequestIdFilter.MDC_KEY),
                data);
    }
}
