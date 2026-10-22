package com.finflow.outbox;

import com.finflow.common.persistence.AuditedEntity;
import com.finflow.events.EventParser;
import com.finflow.events.PaymentEventMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the outbox. MANDATORY propagation is the whole point of the pattern: the event
 * row commits or rolls back together with the business change, so there is never a state change
 * without its event, nor an event for a change that did not happen.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final EventParser events;

    public OutboxWriter(OutboxRepository outbox, EventParser events) {
        this.outbox = outbox;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PaymentEventMessage message) {
        outbox.save(new OutboxEvent(message.id(), "payment", message.data().paymentId(), message.merchantId(),
                message.type(), events.write(message), message.correlationId(), AuditedEntity.now()));
    }
}
