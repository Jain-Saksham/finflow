package com.finflow.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void simultaneousIdenticalRequestsCreateExactlyOnePaymentAndOneProviderAuthorization() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 75_00);
        String idem = UUID.randomUUID().toString();
        String body = "{\"orderId\":\"" + orderId + "\",\"paymentMethodToken\":\"tok_success\"}";
        simulator.setLatencyMs(150); // keep the first request in flight while the others arrive

        int clients = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return httpPost(key, "/api/v1/payments", body, idem);
                }));
            }
            start.countDown();
        }
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (var f : futures) {
            responses.add(f.get());
        }

        // Every response is either the payment (original or replay) or "still in progress".
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isIn(201, 409));
        var created = responses.stream().filter(r -> r.statusCode() == 201).toList();
        assertThat(created).isNotEmpty();
        assertThat(created.stream().map(r -> JsonPath.<String>read(r.body(), "$.id")).distinct()).hasSize(1);
        assertThat(created.stream().filter(r -> r.headers().firstValue("Idempotent-Replayed").isEmpty())).hasSize(1);
        responses.stream().filter(r -> r.statusCode() == 409).forEach(r ->
                assertThat(JsonPath.<String>read(r.body(), "$.code")).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        assertThat(count("select count(*) from payments where order_id = ?::uuid", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from provider_sim.transactions t join payments p on p.id = t.payment_id "
                + "where p.order_id = ?::uuid and t.type = 'AUTHORIZATION'", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries l join payments p on p.id = l.payment_id "
                + "where p.order_id = ?::uuid", orderId)).isEqualTo(2);
    }

    @Test
    void retryDuringAProviderTimeoutIsToldToWaitThenReceivesTheOriginalResult() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 20_00);
        String idem = UUID.randomUUID().toString();
        String body = "{\"orderId\":\"" + orderId + "\",\"paymentMethodToken\":\"tok_timeout\"}";
        simulator.setTimeoutMs(1_000);

        // The client gives up waiting and retries while the server is still blocked on the provider.
        CompletableFuture<HttpResponse<String>> first =
                CompletableFuture.supplyAsync(() -> httpPost(key, "/api/v1/payments", body, idem));
        Thread.sleep(300);
        HttpResponse<String> earlyRetry = httpPost(key, "/api/v1/payments", body, idem);
        assertThat(earlyRetry.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(earlyRetry.body(), "$.code")).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");

        HttpResponse<String> original = first.get();
        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(original.body(), "$.status")).isEqualTo("TIMED_OUT");

        HttpResponse<String> lateRetry = httpPost(key, "/api/v1/payments", body, idem);
        assertThat(lateRetry.statusCode()).isEqualTo(201);
        assertThat(lateRetry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(lateRetry.body()).isEqualTo(original.body());

        assertThat(count("select count(*) from payments where order_id = ?::uuid", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from provider_sim.transactions t join payments p on p.id = t.payment_id "
                + "where p.order_id = ?::uuid", orderId)).isEqualTo(1);
    }

    @Test
    void businessRejectionsAreStoredAndReplayed() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        String idem = UUID.randomUUID().toString();
        String body = "{\"amountMinor\":99999}";

        postJson(key, "/api/v1/payments/" + paymentId + "/refunds", body, idem)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_REFUNDABLE"));
        postJson(key, "/api/v1/payments/" + paymentId + "/refunds", body, idem)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_REFUNDABLE"));
        // Same key, different request: rejected rather than silently replaying the wrong thing.
        postJson(key, "/api/v1/payments/" + paymentId + "/refunds", "{\"amountMinor\":100}", idem)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("select count(*) from refunds where payment_id = ?::uuid", paymentId)).isZero();
    }

    @Test
    void transientFailuresReleaseTheKeySoTheSameKeyCanBeRetried() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "MANUAL").read("$.id");
        String idem = UUID.randomUUID().toString();

        simulator.setOutage(true);
        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null, idem)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        simulator.setOutage(false);

        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null, idem)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        // A retried capture with the same key replays the success instead of failing with 409.
        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null, idem)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(count("select count(*) from ledger_entries where payment_id = ?::uuid", paymentId)).isEqualTo(2);
    }

    @Test
    void retryAfterACrashReturnsTheExistingPaymentInsteadOfChargingAgain() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 30_00);
        String idem = UUID.randomUUID().toString();
        String body = "{\"orderId\":\"" + orderId + "\",\"paymentMethodToken\":\"tok_crash_after_provider_approval\"}";

        postJson(key, "/api/v1/payments", body, idem)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SIMULATED_CRASH"));
        // The 5xx released the idempotency record; the payment row's own unique key catches the retry.
        postJson(key, "/api/v1/payments", body, idem)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("CREATED"));
        assertThat(count("select count(*) from payments where order_id = ?::uuid", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from provider_sim.transactions t join payments p on p.id = t.payment_id "
                + "where p.order_id = ?::uuid", orderId)).isEqualTo(1);
    }

    @Test
    void anAbandonedInProgressRecordCanBeTakenOverAfterItsLease() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 12_00);
        String idem = UUID.randomUUID().toString();
        // Simulate a request that claimed the key and then died: IN_PROGRESS with an expired lease.
        jdbc.update("""
                insert into idempotency_records (merchant_id, idempotency_key, operation, request_fingerprint, status,
                    locked_until, created_at)
                values (?, ?, 'payments.create', ?, 'IN_PROGRESS', now() - interval '1 second', now())""",
                merchantIdOf(key), idem, fingerprintFor(orderId));

        postJson(key, "/api/v1/payments", "{\"orderId\":\"" + orderId + "\",\"paymentMethodToken\":\"tok_success\"}", idem)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    IdempotencyService idempotency;

    private String fingerprintFor(String orderId) {
        return idempotency.fingerprint("payments.create", new com.finflow.payment.PaymentController.CreateFingerprint(
                UUID.fromString(orderId), "tok_success", com.finflow.payment.CaptureMode.AUTOMATIC));
    }
}
