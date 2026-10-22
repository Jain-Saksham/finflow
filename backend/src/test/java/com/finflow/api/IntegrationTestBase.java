package com.finflow.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finflow.merchant.ApiKeys;
import com.finflow.merchant.Merchant;
import com.finflow.merchant.MerchantRepository;
import com.finflow.provider.SimulatorSettings;
import org.junit.jupiter.api.AfterEach;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the full application against a real PostgreSQL (Testcontainers), with Flyway migrations
 * and the provider simulator's artificial latency/timeout delay set to zero. Each test creates its
 * own merchant, so tests are isolated without truncating tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finflow.demo.seed=false",
        // Every cached test context rotates the single demo merchant's key; a fixed key keeps them all
        // valid (the same reason multi-instance deployments must set FINFLOW_DEMO_API_KEY).
        "finflow.demo.api-key=ff_test_integration_demo_key_0123456789abcdef",
        "finflow.simulator.latency-ms=0",
        "finflow.simulator.timeout-ms=0",
        // Background workers that tests drive explicitly (or enable per test) for determinism.
        "finflow.recovery.auto-sync-enabled=false",
        "finflow.recovery.auto-sync-delay=0s",
        "finflow.webhooks.worker-enabled=false",
        "finflow.webhooks.retry-schedule=0s,0s,0s",
        "finflow.reconciliation.schedule-enabled=false",
        "finflow.reconciliation.stale-after=0s",
        "finflow.outbox.poll-interval=100ms",
        "finflow.kafka.consumer-retry-backoff=100ms",
        "finflow.rate-limit.requests-per-minute=100000",
        "finflow.rate-limit.writes-per-minute=100000",
        "finflow.rate-limit.public-per-minute=100000",
        "finflow.ops.token=" + IntegrationTestBase.OPS_TOKEN
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    public static final String OPS_TOKEN = "test-ops-token";

    // One set of containers for the whole run; Spring caches the context across test classes.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.2.2");

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6-alpine").withExposedPorts(6379);

    static {
        Startables.deepStart(POSTGRES, KAFKA, REDIS).join();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MerchantRepository merchants;

    @Autowired
    protected SimulatorSettings simulator;

    @Autowired
    protected JdbcTemplate jdbc;

    @LocalServerPort
    protected int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Real HTTP (not MockMvc) for tests that need genuinely concurrent requests. */
    protected HttpResponse<String> httpPost(String apiKey, String path, String json, String idempotencyKey) {
        try {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (apiKey != null) {
                builder.header("X-API-Key", apiKey);
            }
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected UUID merchantIdOf(String apiKey) {
        return merchants.findByApiKeyHash(ApiKeys.hash(apiKey)).orElseThrow().getId();
    }

    @AfterEach
    void resetSimulator() {
        simulator.reset();
    }

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
