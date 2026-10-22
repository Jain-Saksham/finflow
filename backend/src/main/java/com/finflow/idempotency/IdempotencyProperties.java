package com.finflow.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param lease     how long an IN_PROGRESS record blocks retries before another request may take it
 *                  over (covers a crashed request); must exceed the slowest operation
 * @param retention completed records are replayable for this long, then deleted. After that,
 *                  domain-level unique keys still prevent duplicates; only the exact replay is lost
 */
@ConfigurationProperties("finflow.idempotency")
public record IdempotencyProperties(Duration lease, Duration retention) {
}
