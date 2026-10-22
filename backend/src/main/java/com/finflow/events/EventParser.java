package com.finflow.events;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventParser {

    private final JsonMapper json;

    public EventParser(JsonMapper json) {
        this.json = json;
    }

    /** @throws PoisonMessageException if the payload can never be processed (not retried) */
    public PaymentEventMessage parse(String payload) {
        try {
            PaymentEventMessage message = json.readValue(payload, PaymentEventMessage.class);
            if (message == null || message.id() == null || message.type() == null || message.merchantId() == null
                    || message.data() == null || message.data().paymentId() == null) {
                throw new PoisonMessageException("Event is missing required fields");
            }
            return message;
        } catch (JacksonException | IllegalArgumentException e) {
            throw new PoisonMessageException("Unparseable event: " + e.getMessage());
        }
    }

    public String write(PaymentEventMessage message) {
        return json.writeValueAsString(message);
    }
}
