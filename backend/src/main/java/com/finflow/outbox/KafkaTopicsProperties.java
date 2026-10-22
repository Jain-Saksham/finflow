package com.finflow.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finflow.kafka")
public record KafkaTopicsProperties(String topic, String dltTopic, int partitions, short replicationFactor,
                                    boolean createTopics, int consumerRetries, Duration consumerRetryBackoff) {
}
