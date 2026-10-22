package com.finflow.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param latencyMs artificial latency added to every provider call
 * @param timeoutMs how long a {@code tok_timeout} call hangs before failing
 * @param outage    when true every provider call fails as unavailable (simulated incident)
 */
@ConfigurationProperties("finflow.simulator")
public record SimulatorProperties(long latencyMs, long timeoutMs, boolean outage) {
}
