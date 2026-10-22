package com.finflow.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter in Redis: one key per subject and window, INCR + PEXPIRE in a Lua script so
 * the two steps are atomic. Simple and good enough for abuse protection; a sliding window would be
 * smoother at window boundaries.
 *
 * <p><b>Fails open.</b> If Redis is unavailable, requests are allowed and a metric is incremented.
 * Rate limiting protects capacity, not correctness, so losing it briefly is the right trade-off;
 * every financial invariant is enforced by PostgreSQL regardless.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return current""", Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;

    public RateLimiter(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        this.registry = registry;
    }

    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds) {
    }

    public Decision check(String bucket, String subject, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowStart = now - (now % window.toMillis());
        String key = "rl:" + bucket + ":" + subject + ":" + windowStart;
        try {
            Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(window.toMillis()));
            long used = count == null ? 0 : count;
            long retryAfter = Math.max(1, (windowStart + window.toMillis() - now + 999) / 1000);
            if (used > limit) {
                registry.counter("finflow.ratelimit.rejected", "bucket", bucket).increment();
                return new Decision(false, limit, 0, retryAfter);
            }
            return new Decision(true, limit, limit - used, retryAfter);
        } catch (RuntimeException e) {
            registry.counter("finflow.redis.failures", "use", "rate_limit", "operation", "check").increment();
            log.debug("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Decision(true, limit, limit, 0);
        }
    }
}
