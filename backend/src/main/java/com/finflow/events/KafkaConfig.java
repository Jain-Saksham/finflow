package com.finflow.events;

import com.finflow.outbox.KafkaTopicsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer error handling: transient failures are retried in-process with exponential backoff;
 * after the retries are exhausted, or immediately for a poison message, the record is published to
 * the dead-letter topic with the failure recorded in headers, and the partition moves on.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    public static final String DLT_CONTAINER_FACTORY = "deadLetterContainerFactory";

    @Bean
    @ConditionalOnProperty(name = "finflow.kafka.create-topics", havingValue = "true")
    NewTopic paymentEventsTopic(KafkaTopicsProperties topics) {
        return TopicBuilder.name(topics.topic()).partitions(topics.partitions()).replicas(topics.replicationFactor()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "finflow.kafka.create-topics", havingValue = "true")
    NewTopic paymentEventsDeadLetterTopic(KafkaTopicsProperties topics) {
        return TopicBuilder.name(topics.dltTopic()).partitions(1).replicas(topics.replicationFactor()).build();
    }

    /** Picked up by Boot's auto-configured listener container factory. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, KafkaTopicsProperties topics,
                                          MeterRegistry registry) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(topics.dltTopic(), -1)); // -1: let Kafka choose the partition
        var backOff = new ExponentialBackOffWithMaxRetries(topics.consumerRetries());
        backOff.setInitialInterval(topics.consumerRetryBackoff().toMillis());
        backOff.setMultiplier(2.0);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonMessageException.class);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                registry.counter("finflow.kafka.consumer.retries", "topic", record.topic()).increment();
                log.warn("Consumer attempt {} failed for {}-{}@{}: {}", deliveryAttempt, record.topic(),
                        record.partition(), record.offset(), ex.getMessage());
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                registry.counter("finflow.kafka.consumer.dead_lettered", "topic", record.topic()).increment();
                log.error("Record {}-{}@{} sent to dead-letter topic: {}", record.topic(), record.partition(),
                        record.offset(), ex.getMessage());
            }
        });
        return handler;
    }

    /**
     * The DLT recorder gets its own factory without a dead-letter recoverer: a failure while
     * recording a dead letter must never loop back into the DLT. It retries, then logs.
     */
    @Bean(DLT_CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> deadLetterContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 5)));
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
