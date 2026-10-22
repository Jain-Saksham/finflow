package com.finflow.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finflow.outbox")
public record OutboxProperties(boolean enabled, Duration pollInterval, int batchSize, int maxAttempts,
                               Duration sendTimeout) {
}
