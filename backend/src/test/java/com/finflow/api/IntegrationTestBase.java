package com.finflow.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finflow.merchant.ApiKeys;
import com.finflow.merchant.Merchant;
import com.finflow.merchant.MerchantRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the full application against a real PostgreSQL (Testcontainers), with Flyway migrations
 * and the provider simulator's artificial latency/timeout delay set to zero. Each test creates its
 * own merchant, so tests are isolated without truncating tables.
 */
@SpringBootTest(properties = {
        "finflow.demo.seed=false",
        "finflow.simulator.latency-ms=0",
        "finflow.simulator.timeout-ms=0"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    // One container for the whole test run; Spring caches the context across test classes.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MerchantRepository merchants;

    protected String newMerchantKey() {
        String key = ApiKeys.generate();
        merchants.save(new Merchant("Test merchant " + UUID.randomUUID(), "USD", false, key));
        return key;
    }

    protected ResultActions postJson(String apiKey, String url, String json) throws Exception {
        return postJson(apiKey, url, json, null);
    }

    protected ResultActions postJson(String apiKey, String url, String json, String idempotencyKey) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json == null ? "{}" : json);
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    protected ResultActions getJson(String apiKey, String url) throws Exception {
        var request = get(url);
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        return mvc.perform(request);
    }

    protected static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString());
    }

    protected String createOrder(String apiKey, long amountMinor) throws Exception {
        String body = """
                {"merchantReference":"ORD-%s","amountMinor":%d,"currency":"USD","description":"Test order"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), amountMinor);
        MvcResult result = postJson(apiKey, "/api/v1/orders", body).andReturn();
        return json(result).read("$.id");
    }

    protected ResultActions pay(String apiKey, String orderId, String token, String captureMode) throws Exception {
        String body = """
                {"orderId":"%s","paymentMethodToken":"%s","captureMode":"%s"}""".formatted(orderId, token, captureMode);
        return postJson(apiKey, "/api/v1/payments", body, UUID.randomUUID().toString());
    }

    protected DocumentContext payAndRead(String apiKey, String orderId, String token, String captureMode) throws Exception {
        return json(pay(apiKey, orderId, token, captureMode).andReturn());
    }

    protected ResultActions refund(String apiKey, String paymentId, Long amountMinor) throws Exception {
        String body = amountMinor == null ? "{}" : "{\"amountMinor\":" + amountMinor + "}";
        return postJson(apiKey, "/api/v1/payments/" + paymentId + "/refunds", body, UUID.randomUUID().toString());
    }
}
