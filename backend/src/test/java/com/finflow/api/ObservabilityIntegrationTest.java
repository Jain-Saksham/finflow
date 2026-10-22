package com.finflow.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class ObservabilityIntegrationTest extends IntegrationTestBase {

    @Test
    void healthProbesAreExposed() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void businessMetricsArePublishedForPrometheus() throws Exception {
        String key = newMerchantKey();
        pay(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC");
        pay(key, createOrder(key, 10_00), "tok_decline_do_not_honor", "AUTOMATIC");

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("finflow_payment_events_total{")))
                .andExpect(content().string(containsString("finflow_provider_latency_seconds_bucket{")))
                .andExpect(content().string(containsString("finflow_payment_processing_seconds_count{")))
                .andExpect(content().string(containsString("finflow_outbox_lag_seconds")))
                .andExpect(content().string(containsString("finflow_webhook_deliveries_due")))
                .andExpect(content().string(containsString("finflow_idempotency_requests_total{")))
                .andExpect(content().string(containsString("kafka_consumer_fetch_manager_records_lag_max")));
    }

    @Test
    void clientRequestIdIsEchoedForCorrelation() throws Exception {
        mvc.perform(get("/api/v1/simulator/scenarios").header("X-Request-Id", "trace-me-12345"))
                .andExpect(header().string("X-Request-Id", "trace-me-12345"));
    }
}
