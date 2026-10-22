package com.finflow.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs: outbox publisher, webhook worker, reconciliation, idempotency cleanup. With
 * virtual threads enabled, Boot runs each scheduled task on its own virtual thread, so a slow
 * reconciliation run never delays the outbox publisher.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "finflow.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
