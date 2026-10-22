package com.finflow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists payment history events in the same transaction as the state change they describe. */
@Component
public class PaymentHistory {

    private static final Logger log = LoggerFactory.getLogger(PaymentHistory.class);

    private final PaymentEventRepository events;

    public PaymentHistory(PaymentEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(PaymentEvent event) {
        events.save(event);
        log.info("payment={} event={} {} -> {}", event.getPaymentId(), event.getType(), event.getFromStatus(),
                event.getToStatus());
    }
}
