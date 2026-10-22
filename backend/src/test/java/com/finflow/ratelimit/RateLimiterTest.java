package com.finflow.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimiter limiter = new RateLimiter(redis, registry);

    @SuppressWarnings("unchecked")
    @Test
    void rejectsOnceTheWindowCountExceedsTheLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L, 4L);
        assertThat(limiter.check("b", "s", 3, Duration.ofMinutes(1)).allowed()).isTrue();
        var denied = limiter.check("b", "s", 3, Duration.ofMinutes(1));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        assertThat(limiter.check("b", "s", 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(registry.counter("finflow.redis.failures", "use", "rate_limit", "operation", "check").count())
                .isEqualTo(1.0);
    }
}
