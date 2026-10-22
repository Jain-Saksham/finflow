package com.finflow.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKeyTtl    how long a resolved API key is trusted without hitting PostgreSQL. Bounds how
 *                     long a rotated key keeps working if the eviction at rotation is lost
 * @param dashboardTtl upper bound on dashboard staleness; changes also evict it after commit
 */
@ConfigurationProperties("finflow.cache")
public record CacheProperties(Duration apiKeyTtl, Duration dashboardTtl) {
}
