package com.finflow.events;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/** Makes dead letters visible and replayable by copying them into the dead_letters table. */
@Component
public class DeadLetterListener {

    public static final String GROUP = "finflow-dlt-recorder";

    private final DeadLetterStore store;

    public DeadLetterListener(DeadLetterStore store) {
        this.store = store;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = "${finflow.kafka.dlt-topic}",
            containerFactory = KafkaConfig.DLT_CONTAINER_FACTORY)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        store.record(record.topic(), record.partition(), record.offset(), record.key(), record.value(),
                text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                text(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
                text(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    private static String text(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
