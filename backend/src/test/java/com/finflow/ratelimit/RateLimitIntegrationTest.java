package com.finflow.ratelimit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Separate application context with tiny limits. */
@TestPropertySource(properties = {
        "finflow.rate-limit.public-per-minute=3",
        "finflow.rate-limit.writes-per-minute=2"
})
class RateLimitIntegrationTest extends IntegrationTestBase {

    @Test
    void publicEndpointsAreLimitedPerClientIp() throws Exception {
        // A unique client address: counters live in the Redis shared by every test context.
        String ip = "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + ".7";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/demo/session").with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/demo/session").with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void writesAreLimitedPerMerchantWhileReadsContinue() throws Exception {
        String key = newMerchantKey();
        String other = newMerchantKey();
        createOrder(key, 100);
        createOrder(key, 100);
        postJson(key, "/api/v1/orders", "{\"merchantReference\":\"X-1\",\"amountMinor\":100,\"currency\":\"USD\"}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
        getJson(key, "/api/v1/orders").andExpect(status().isOk());
        // Limits are per merchant: another merchant is unaffected.
        postJson(other, "/api/v1/orders", "{\"merchantReference\":\"X-1\",\"amountMinor\":100,\"currency\":\"USD\"}")
                .andExpect(status().isCreated());
    }
}
