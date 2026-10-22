package com.finflow.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finflow.rate-limit")
public record RateLimitProperties(boolean enabled, int requestsPerMinute, int writesPerMinute, int publicPerMinute) {
}
