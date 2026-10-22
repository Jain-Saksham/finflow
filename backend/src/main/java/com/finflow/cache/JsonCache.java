package com.finflow.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-through JSON cache on Redis. Every operation fails open: if Redis is slow (250 ms timeout)
 * or down, the value is simply loaded from PostgreSQL. Only derived, re-computable data is cached;
 * nothing financial is ever written to Redis as a source of truth.
 */
@Component
public class JsonCache {

    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MeterRegistry registry;

    public JsonCache(StringRedisTemplate redis, JsonMapper json, MeterRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.registry = registry;
    }

    public <T> T getOrLoad(String cache, String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        String redisKey = "cache:" + cache + ":" + key;
        Optional<T> cached = read(cache, redisKey, type);
        if (cached.isPresent()) {
            registry.counter("finflow.cache.requests", "cache", cache, "result", "hit").increment();
            return cached.get();
        }
        registry.counter("finflow.cache.requests", "cache", cache, "result", "miss").increment();
        T value = loader.get();
        if (value != null) {
            try {
                redis.opsForValue().set(redisKey, json.writeValueAsString(value), ttl);
            } catch (RuntimeException e) {
                failure(cache, "write", e);
            }
        }
        return value;
    }

    public void evict(String cache, String key) {
        try {
            redis.delete("cache:" + cache + ":" + key);
        } catch (RuntimeException e) {
            // The entry expires by TTL; until then a stale value may be served (documented trade-off).
            failure(cache, "evict", e);
        }
    }

    private <T> Optional<T> read(String cache, String redisKey, Class<T> type) {
        try {
            String raw = redis.opsForValue().get(redisKey);
            return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, type));
        } catch (RuntimeException e) {
            failure(cache, "read", e);
            return Optional.empty();
        }
    }

    private void failure(String cache, String operation, RuntimeException e) {
        registry.counter("finflow.redis.failures", "use", "cache", "operation", operation).increment();
        log.debug("Redis cache {} {} failed, falling back to the database: {}", cache, operation, e.getMessage());
    }
}
