package com.finflow.webhook;

import com.finflow.common.web.RequestIdFilter;
import com.finflow.events.EventParser;
import com.finflow.events.PaymentEventMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns each payment event into one pending delivery per enabled webhook endpoint. Kafka delivers
 * at least once; a redelivered event hits the (endpoint_id, event_id) unique key and is ignored.
 */
@Component
public class WebhookFanoutListener {

    public static final String GROUP = "finflow-webhook-fanout";
    private static final Logger log = LoggerFactory.getLogger(WebhookFanoutListener.class);

    private final EventParser parser;
    private final WebhookDeliveryStore deliveries;
    private final MeterRegistry registry;

    public WebhookFanoutListener(EventParser parser, WebhookDeliveryStore deliveries, MeterRegistry registry) {
        this.parser = parser;
        this.deliveries = deliveries;
        this.registry = registry;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${finflow.kafka.topic}")
    public void onEvent(ConsumerRecord<String, String> record) {
        PaymentEventMessage event = parser.parse(record.value());
        if (event.correlationId() != null) {
            MDC.put(RequestIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            int created = deliveries.fanOut(event.merchantId(), event.id(), event.type(), event.data().paymentId(),
                    record.value(), event.correlationId());
            if (created == 0) {
                registry.counter("finflow.kafka.consumer.duplicates", "group", GROUP).increment();
                log.debug("Event {} produced no new deliveries (duplicate or no endpoints)", event.id());
            }
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }
}
