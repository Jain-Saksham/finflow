package com.finflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import com.finflow.events.PaymentEventMessage;
import com.finflow.ledger.Journal;
import com.finflow.ledger.JournalType;
import com.finflow.ledger.LedgerService;
import com.finflow.webhook.WebhookDeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * Failures injected with Mockito spies at the exact points where distributed-systems bugs hide:
 * between a business change and its event, between Kafka and the outbox table, inside consumers,
 * and between a provider call and the local commit.
 */
class FailureInjectionIntegrationTest extends IntegrationTestBase {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @MockitoSpyBean
    OutboxWriter outboxWriter;

    @MockitoSpyBean
    OutboxStore outboxStore;

    @MockitoSpyBean
    WebhookDeliveryStore deliveryStore;

    @MockitoSpyBean
    LedgerService ledger;

    @Autowired
    MeterRegistry registry;

    /** Stub the spy behind the transactional proxy; stubbing through the proxy would hit the tx interceptor. */
    private static <T> T target(T bean) {
        return AopTestUtils.getUltimateTargetObject(bean);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private void endpoint(String key) throws Exception {
        postJson(key, "/api/v1/webhook-endpoints", "{\"simulatedBehavior\":\"ALWAYS_SUCCEED\"}").andExpect(status().isCreated());
    }

    @Test
    void ifTheEventCannotBeWrittenTheStateChangeAndLedgerPostingRollBackToo() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndRead(key, createOrder(key, 40_00), "tok_success", "MANUAL").read("$.id");
        doAnswer(inv -> {
            PaymentEventMessage m = inv.getArgument(0);
            if (m.type().equals("payment.captured") && m.data().paymentId().toString().equals(paymentId)) {
                throw new IllegalStateException("simulated outbox failure");
            }
            return inv.callRealMethod();
        }).when(target(outboxWriter)).append(any());

        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null).andExpect(status().isInternalServerError());

        // Atomic: no CAPTURED status, no history row, no ledger journal, no outbox event, order still open.
        getJson(key, "/api/v1/payments/" + paymentId).andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(count("select count(*) from payment_events where payment_id = ?::uuid and type = 'CAPTURED'", paymentId)).isZero();
        assertThat(count("select count(*) from ledger_entries where payment_id = ?::uuid", paymentId)).isZero();
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'payment.captured'",
                paymentId)).isZero();

        // The provider, an independent system, did capture: reconciliation detects and repairs it.
        org.mockito.Mockito.reset(target(outboxWriter));
        postJson(key, "/api/v1/reconciliation/runs", null)
                .andExpect(jsonPath("$.findings[0].type").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.findings[0].resolution").value("AUTO_RESOLVED"));
        getJson(key, "/api/v1/payments/" + paymentId).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(count("select count(*) from ledger_entries where payment_id = ?::uuid", paymentId)).isEqualTo(2);
    }

    @Test
    void whileKafkaIsDownEventsWaitInTheOutboxAndArePublishedOnceItRecovers() throws Exception {
        String key = newMerchantKey();
        endpoint(key);
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec(); // a real broker outage
        String paymentId;
        try {
            // The API is unaffected by the outage: the payment completes and its events are queued.
            paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
            getJson(key, "/api/v1/payments/" + paymentId).andExpect(jsonPath("$.status").value("CAPTURED"));
            // The publisher keeps trying and recording failures; nothing for this payment gets out.
            await().atMost(WAIT).until(() -> count("select count(*) from outbox_events where status = 'PENDING' "
                    + "and publish_attempts >= 1") >= 1);
            assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and status = 'PUBLISHED'",
                    paymentId)).isZero();
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        String id = paymentId;
        await().atMost(Duration.ofSeconds(60)).until(() -> count("select count(*) from outbox_events "
                + "where aggregate_id = ?::uuid and status = 'PUBLISHED'", id) == 3);
        // Nothing was lost: every event written during the outage got out exactly as recorded.
        assertThat(jdbc.queryForList("select event_type from outbox_events where aggregate_id = ?::uuid "
                + "order by sequence_no", String.class, paymentId))
                .containsExactly("payment.created", "payment.authorized", "payment.captured");
        await().atMost(Duration.ofSeconds(60)).until(() -> count("select count(*) from webhook_deliveries "
                + "where payment_id = ?::uuid", id) == 3);
    }

    @Test
    void aCrashAfterKafkaAckedButBeforeTheRowWasMarkedPublishedOnlyCausesAHarmlessDuplicate() throws Exception {
        String key = newMerchantKey();
        endpoint(key);
        String orderId = createOrder(key, 10_00);
        AtomicInteger failuresLeft = new AtomicInteger(1);
        doAnswer(inv -> {
            java.util.List<UUID> ids = inv.getArgument(0);
            for (UUID eventId : ids) {
                String type = jdbc.queryForObject("select event_type from outbox_events where id = ?", String.class, eventId);
                String payload = jdbc.queryForObject("select payload from outbox_events where id = ?", String.class, eventId);
                if ("payment.created".equals(type) && payload.contains(orderId) && failuresLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("simulated crash after Kafka ack");
                }
            }
            return inv.callRealMethod();
        }).when(target(outboxStore)).markPublished(any());

        String paymentId = payAndRead(key, orderId, "tok_success", "AUTOMATIC").read("$.id");
        await().atMost(WAIT).until(() -> count("select count(*) from outbox_events where aggregate_id = ?::uuid "
                + "and status = 'PUBLISHED'", paymentId) == 3);
        assertThat(failuresLeft.get()).isNegative(); // the failure really happened, so payment.created went out twice
        Thread.sleep(1_500);
        assertThat(count("select count(*) from webhook_deliveries where payment_id = ?::uuid", paymentId)).isEqualTo(3);
        assertThat(registry.find("finflow.kafka.consumer.duplicates").counter()).isNotNull();
    }

    @Test
    void aTransientConsumerFailureIsRetriedAndThenSucceeds() throws Exception {
        String key = newMerchantKey();
        endpoint(key);
        String orderId = createOrder(key, 10_00);
        AtomicInteger failuresLeft = new AtomicInteger(2);
        double retriesBefore = registry.counter("finflow.kafka.consumer.retries", "topic", "finflow.payment-events").count();
        doAnswer(inv -> {
            String payload = inv.getArgument(4);
            if (payload.contains(orderId) && failuresLeft.getAndDecrement() > 0) {
                throw new org.springframework.dao.TransientDataAccessResourceException("simulated database blip");
            }
            return inv.callRealMethod();
        }).when(target(deliveryStore)).fanOut(any(), any(), any(), any(), any(), any());

        String paymentId = payAndRead(key, orderId, "tok_decline_do_not_honor", "AUTOMATIC").read("$.id");
        await().atMost(WAIT).until(() -> count("select count(*) from webhook_deliveries where payment_id = ?::uuid",
                paymentId) == 2);
        assertThat(registry.counter("finflow.kafka.consumer.retries", "topic", "finflow.payment-events").count())
                .isGreaterThanOrEqualTo(retriesBefore + 2);
        assertThat(count("select count(*) from dead_letters where payload like ?", "%" + paymentId + "%")).isZero();
    }

    @Test
    void aCrashAfterTheProviderRefundedIsRepairedByReconciliation() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndRead(key, createOrder(key, 50_00), "tok_success", "AUTOMATIC").read("$.id");
        doAnswer(inv -> {
            Journal journal = inv.getArgument(0);
            if (journal.type() == JournalType.REFUND && journal.paymentId().toString().equals(paymentId)) {
                throw new IllegalStateException("simulated crash before the refund commit");
            }
            return inv.callRealMethod();
        }).when(target(ledger)).post(any());

        refund(key, paymentId, 20_00L).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("select status from refunds where payment_id = ?::uuid", String.class, paymentId))
                .isEqualTo("PENDING");
        assertThat(count("select count(*) from provider_sim.transactions where payment_id = ?::uuid and type = 'REFUND'",
                paymentId)).isEqualTo(1);

        org.mockito.Mockito.reset(target(ledger));
        postJson(key, "/api/v1/reconciliation/runs", null)
                .andExpect(jsonPath("$.findings[0].type").value("PENDING_REFUND"))
                .andExpect(jsonPath("$.findings[0].resolution").value("AUTO_RESOLVED"));
        getJson(key, "/api/v1/payments/" + paymentId)
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmountMinor").value(20_00));
        assertThat(count("select count(*) from ledger_entries where payment_id = ?::uuid and journal_type = 'REFUND'",
                paymentId)).isEqualTo(2);
        postJson(key, "/api/v1/reconciliation/runs", null).andExpect(jsonPath("$.run.findingsTotal").value(0));
    }
}
