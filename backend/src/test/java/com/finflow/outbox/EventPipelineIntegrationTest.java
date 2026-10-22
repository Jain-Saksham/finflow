package com.finflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import com.finflow.events.DeadLetterListener;
import com.finflow.events.TimeoutRecoveryListener;
import com.finflow.webhook.WebhookDeliveryStore;
import com.finflow.webhook.WebhookDeliveryWorker;
import com.finflow.webhook.WebhookFanoutListener;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

class EventPipelineIntegrationTest extends IntegrationTestBase {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaTopicsProperties topics;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    WebhookDeliveryWorker worker;

    @Autowired
    WebhookDeliveryStore deliveryStore;

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String endpoint(String key, String behavior) throws Exception {
        return json(postJson(key, "/api/v1/webhook-endpoints", "{\"simulatedBehavior\":\"" + behavior + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn()).read("$.id");
    }

    private void awaitAllPublished(String paymentId) {
        await().atMost(WAIT).until(() -> count("select count(*) from outbox_events where aggregate_id = ?::uuid "
                + "and status <> 'PUBLISHED'", paymentId) == 0);
    }

    private void drainWorker() {
        for (int i = 0; i < 10 && worker.runOnce() > 0; i++) {
            // retry schedule is 0s in tests, so each run performs the next attempt
        }
    }

    @Test
    void everyStateChangeWritesAnOutboxEventThatIsPublished() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");

        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid", paymentId))
                .isEqualTo(count("select count(*) from payment_events where payment_id = ?::uuid", paymentId))
                .isEqualTo(3);
        assertThat(jdbc.queryForList("select event_type from outbox_events where aggregate_id = ?::uuid order by sequence_no",
                String.class, paymentId)).containsExactly("payment.created", "payment.authorized", "payment.captured");
        awaitAllPublished(paymentId);
    }

    @Test
    void eventsBecomeSignedWebhooksAndDuplicateKafkaDeliveryCreatesNothing() throws Exception {
        String key = newMerchantKey();
        String endpointId = endpoint(key, "ALWAYS_SUCCEED");
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        await().atMost(WAIT).until(() -> count("select count(*) from webhook_deliveries where payment_id = ?::uuid",
                paymentId) == 3);

        // Redeliver every event for this payment to Kafka, as a publisher crash before marking PUBLISHED would.
        for (String payload : jdbc.queryForList("select payload from outbox_events where aggregate_id = ?::uuid",
                String.class, paymentId)) {
            kafka.send(topics.topic(), paymentId, payload).get();
        }
        Thread.sleep(1_500);
        assertThat(count("select count(*) from webhook_deliveries where payment_id = ?::uuid", paymentId)).isEqualTo(3);

        drainWorker();
        getJson(key, "/api/v1/webhook-deliveries?paymentId=" + paymentId)
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.status=='SUCCEEDED')]", org.hamcrest.Matchers.hasSize(3)));
        // The receiver verified each HMAC signature (it answers 401 otherwise) and processed each event once.
        assertThat(count("select count(*) from webhook_receipts where endpoint_id = ?::uuid and processed_at is not null",
                endpointId)).isEqualTo(3);
    }

    @Test
    void failedAttemptsAreRetriedOnScheduleWithFullHistory() throws Exception {
        String key = newMerchantKey();
        endpoint(key, "FAIL_FIRST_ATTEMPT");
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_decline_do_not_honor", "AUTOMATIC").read("$.id");
        await().atMost(WAIT).until(() -> count("select count(*) from webhook_deliveries where payment_id = ?::uuid",
                paymentId) == 2);

        worker.runOnce();
        assertThat(count("select count(*) from webhook_deliveries where payment_id = ?::uuid and status = 'RETRYING'",
                paymentId)).isEqualTo(2);
        worker.runOnce();
        String deliveryId = jdbc.queryForObject("select id::text from webhook_deliveries where payment_id = ?::uuid "
                + "and event_type = 'payment.declined'", String.class, paymentId);
        getJson(key, "/api/v1/webhook-deliveries/" + deliveryId)
                .andExpect(jsonPath("$.delivery.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.attempts[0].responseStatus").value(503))
                .andExpect(jsonPath("$.attempts[1].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.maxAttempts").value(4));
    }

    @Test
    void permanentlyFailingDeliveriesEndFailedAndCanBeRedeliveredWithReceiverDedup() throws Exception {
        String key = newMerchantKey();
        endpoint(key, "ALWAYS_FAIL");
        String successEndpoint = endpoint(key, "ALWAYS_SUCCEED");
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_provider_unavailable", "AUTOMATIC").read("$.id");
        await().atMost(WAIT).until(() -> count("select count(*) from webhook_deliveries where payment_id = ?::uuid",
                paymentId) == 4);
        drainWorker();

        String failed = jdbc.queryForObject("select id::text from webhook_deliveries where payment_id = ?::uuid and "
                + "status = 'FAILED' limit 1", String.class, paymentId);
        getJson(key, "/api/v1/webhook-deliveries/" + failed).andExpect(jsonPath("$.attempts.length()").value(4));

        // Redelivering an already-processed event: the receiver acknowledges it without processing twice.
        String succeeded = jdbc.queryForObject("select id::text from webhook_deliveries where payment_id = ?::uuid and "
                + "endpoint_id = ?::uuid limit 1", String.class, paymentId, successEndpoint);
        postJson(key, "/api/v1/webhook-deliveries/" + succeeded + "/redeliver", null)
                .andExpect(jsonPath("$.delivery.status").value("PENDING"));
        drainWorker();
        getJson(key, "/api/v1/webhook-deliveries/" + succeeded)
                .andExpect(jsonPath("$.delivery.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts[1].responseBody", org.hamcrest.Matchers.containsString("duplicate_ignored")));
        assertThat(count("select sum(duplicate_deliveries) from webhook_receipts where endpoint_id = ?::uuid",
                successEndpoint)).isEqualTo(1);
    }

    @Test
    void aCrashedWorkersDeliveryIsPickedUpAfterItsLeaseExpires() throws Exception {
        String key = newMerchantKey();
        endpoint(key, "ALWAYS_SUCCEED");
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_decline_expired_card", "AUTOMATIC").read("$.id");
        await().atMost(WAIT).until(() -> count("select count(*) from webhook_deliveries where payment_id = ?::uuid",
                paymentId) == 2);

        // A worker claims everything due, then "dies" without delivering.
        var claimed = deliveryStore.claimDue(1000, Duration.ofMillis(400));
        assertThat(claimed).isNotEmpty();
        assertThat(worker.runOnce()).isZero(); // still leased: nobody else may touch them
        Thread.sleep(500);
        drainWorker();
        assertThat(count("select count(*) from webhook_deliveries where payment_id = ?::uuid and status = 'SUCCEEDED'",
                paymentId)).isEqualTo(2);
    }

    @Test
    void consumerRestartResumesFromItsCommittedOffsetWithoutLosingEvents() throws Exception {
        String key = newMerchantKey();
        endpoint(key, "ALWAYS_SUCCEED");
        var container = listeners.getListenerContainer(WebhookFanoutListener.GROUP);
        container.stop();
        try {
            String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
            awaitAllPublished(paymentId);
            Thread.sleep(500);
            assertThat(count("select count(*) from webhook_deliveries where payment_id = ?::uuid", paymentId)).isZero();

            container.start();
            await().atMost(WAIT).until(() -> count("select count(*) from webhook_deliveries where payment_id = ?::uuid",
                    paymentId) == 3);
        } finally {
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    @Test
    void poisonMessagesAreDeadLetteredAndCanBeReplayedByAnOperator() throws Exception {
        String marker = "poison-" + UUID.randomUUID();
        kafka.send(topics.topic(), marker, "{this is not json " + marker).get();

        await().atMost(WAIT).until(() -> count("select count(*) from dead_letters where message_key = ?", marker) >= 1);
        var row = jdbc.queryForMap("select id, consumer_group, error_class, original_topic from dead_letters "
                + "where message_key = ? limit 1", marker);
        assertThat(row.get("consumer_group")).isEqualTo(WebhookFanoutListener.GROUP);
        assertThat((String) row.get("error_class")).contains("ListenerExecutionFailedException");
        assertThat(row.get("original_topic")).isEqualTo(topics.topic());

        long id = ((Number) row.get("id")).longValue();
        mvc.perform(post("/api/ops/dead-letters/" + id + "/replay")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ops/dead-letters/" + id + "/replay").header("X-Ops-Token", OPS_TOKEN))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ops/dead-letters").header("X-Ops-Token", OPS_TOKEN)).andExpect(status().isOk());
        assertThat(count("select replay_count from dead_letters where id = ?", id)).isEqualTo(1);
        // Still poison after replay, so it lands in the DLT again rather than blocking the partition.
        await().atMost(WAIT).until(() -> count("select count(*) from dead_letters where message_key = ?", marker) >= 2);
        assertThat(listeners.getListenerContainer(DeadLetterListener.GROUP).isRunning()).isTrue();
    }

    @Test
    void timedOutPaymentsAreResolvedAsynchronouslyByTheRecoveryConsumer() throws Exception {
        String key = newMerchantKey();
        var container = listeners.getListenerContainer(TimeoutRecoveryListener.GROUP);
        container.start();
        try {
            String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_timeout", "AUTOMATIC").read("$.id");
            await().atMost(WAIT).untilAsserted(() -> getJson(key, "/api/v1/payments/" + paymentId)
                    .andExpect(jsonPath("$.status").value("CAPTURED")));
            getJson(key, "/api/v1/payments/" + paymentId + "/events")
                    .andExpect(jsonPath("$[*].type", org.hamcrest.Matchers.contains(
                            "CREATED", "TIMED_OUT", "AUTHORIZED", "CAPTURED")));
        } finally {
            container.stop();
        }
    }
}
