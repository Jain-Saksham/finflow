package com.finflow.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Redis is paused (not just stopped), the nastier case: connections hang instead of failing fast. */
class RedisOutageIntegrationTest extends IntegrationTestBase {

    @Autowired
    MeterRegistry registry;

    private double redisFailures() {
        return registry.find("finflow.redis.failures").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void paymentsRefundsAndTheLedgerStayCorrectWhileRedisIsUnavailable() throws Exception {
        String key = newMerchantKey(); // resolved once while Redis is up, so the key is also cached
        getJson(key, "/api/v1/merchants/me").andExpect(status().isOk());
        double failuresBefore = redisFailures();

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long started = System.currentTimeMillis();
            String paymentId = payAndRead(key, createOrder(key, 80_00), "tok_success", "AUTOMATIC").read("$.id");
            refund(key, paymentId, 30_00L).andExpect(status().isCreated());
            getJson(key, "/api/v1/payments/" + paymentId)
                    .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                    .andExpect(jsonPath("$.refundedAmountMinor").value(30_00));
            getJson(key, "/api/v1/dashboard/summary").andExpect(status().isOk());
            getJson(key, "/api/v1/ledger/balances")
                    .andExpect(jsonPath("$.checks[0].balanced").value(true))
                    .andExpect(jsonPath("$.accounts[?(@.account=='MERCHANT_BALANCE')].balanceMinor").value(50_00));
            // Degraded, not stuck: each Redis call gives up after the 250 ms command timeout.
            assertThat(System.currentTimeMillis() - started).isLessThan(15_000);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(redisFailures()).isGreaterThan(failuresBefore);

        // Back to normal after recovery.
        getJson(key, "/api/v1/dashboard/summary").andExpect(status().isOk());
    }
}
