package com.finflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finflow.api.IntegrationTestBase;
import com.finflow.provider.ProviderRecords;
import com.jayway.jsonpath.DocumentContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationIntegrationTest extends IntegrationTestBase {

    @Autowired
    ProviderRecords providerRecords;

    private DocumentContext reconcile(String key) throws Exception {
        return json(postJson(key, "/api/v1/reconciliation/runs", null).andExpect(status().isOk()).andReturn());
    }

    @Test
    void consistentMerchantProducesACleanReport() throws Exception {
        String key = newMerchantKey();
        String a = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        refund(key, a, 2_00L);
        pay(key, createOrder(key, 10_00), "tok_decline_expired_card", "AUTOMATIC");
        pay(key, createOrder(key, 10_00), "tok_provider_unavailable", "AUTOMATIC");
        pay(key, createOrder(key, 10_00), "tok_success", "MANUAL");

        DocumentContext report = reconcile(key);
        assertThat(report.<String>read("$.run.status")).isEqualTo("COMPLETED");
        assertThat(report.<Integer>read("$.run.findingsTotal")).isZero();
        assertThat(report.<Integer>read("$.run.matchedPayments")).isEqualTo(4);
    }

    @Test
    void crashAfterProviderApprovalIsRecoveredAutomatically() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 25_00);
        pay(key, orderId, "tok_crash_after_provider_approval", "AUTOMATIC").andExpect(status().isInternalServerError());
        String paymentId = jdbc.queryForObject("select id::text from payments where order_id = ?::uuid", String.class, orderId);
        getJson(key, "/api/v1/payments/" + paymentId).andExpect(jsonPath("$.status").value("CREATED"));

        DocumentContext report = reconcile(key);
        assertThat(report.<String>read("$.findings[0].type")).isEqualTo("UNRESOLVED_PAYMENT");
        assertThat(report.<String>read("$.findings[0].resolution")).isEqualTo("AUTO_RESOLVED");
        getJson(key, "/api/v1/payments/" + paymentId).andExpect(jsonPath("$.status").value("CAPTURED"));
        getJson(key, "/api/v1/orders/" + orderId).andExpect(jsonPath("$.status").value("PAID"));

        // The repair was itself correct: a second run is clean.
        assertThat(reconcile(key).<Integer>read("$.run.findingsTotal")).isZero();
    }

    @Test
    void providerSideAnomaliesAreReportedAsUnrecoverableAndExportable() throws Exception {
        String key = newMerchantKey();
        UUID merchantId = merchantIdOf(key);
        String duplicated = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        String lost = payAndRead(key, createOrder(key, 20_00), "tok_success", "AUTOMATIC").read("$.id");
        providerRecords.injectDuplicateCapture(UUID.fromString(duplicated));
        providerRecords.deleteCapture(UUID.fromString(lost));
        providerRecords.injectOrphanCapture(merchantId, 42_00, "USD");

        DocumentContext report = reconcile(key);
        String runId = report.read("$.run.id");
        postJson(key, "/api/v1/reconciliation/runs", null); // second run keeps reporting them: nothing was auto-"fixed"
        getJson(key, "/api/v1/reconciliation/runs/" + runId)
                .andExpect(jsonPath("$.findings[*].type", containsInAnyOrder(
                        "DUPLICATE_PROVIDER_CAPTURE", "MISSING_AT_PROVIDER", "ORPHAN_PROVIDER_TRANSACTION")))
                .andExpect(jsonPath("$.findings[*].resolution", containsInAnyOrder("OPEN", "OPEN", "OPEN")))
                .andExpect(jsonPath("$.findings[*].severity", containsInAnyOrder("CRITICAL", "CRITICAL", "CRITICAL")))
                .andExpect(jsonPath("$.run.openFindings").value(3));
        getJson(key, "/api/v1/reconciliation/runs").andExpect(jsonPath("$.totalElements").value(2));
        getJson(key, "/api/v1/reconciliation/runs/" + runId + "/report.csv")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DUPLICATE_PROVIDER_CAPTURE,CRITICAL,false,OPEN," + duplicated)));
    }

    @Test
    void stalePendingRefundTheProviderNeverSawIsFailedAndItsReservationReleased() throws Exception {
        String key = newMerchantKey();
        UUID merchantId = merchantIdOf(key);
        String paymentId = payAndRead(key, createOrder(key, 10_00), "tok_success", "AUTOMATIC").read("$.id");
        // A refund reserved locally whose provider call never happened (process died in between).
        UUID refundId = UUID.randomUUID();
        jdbc.update("""
                insert into refunds (id, merchant_id, payment_id, amount_minor, currency, status, idempotency_key,
                    version, created_at, updated_at)
                values (?, ?, ?::uuid, 400, 'USD', 'PENDING', ?, 0, now(), now())""",
                refundId, merchantId, paymentId, "lost-" + refundId);

        refund(key, paymentId, 10_00L).andExpect(status().isUnprocessableContent()); // reservation still counts

        DocumentContext report = reconcile(key);
        assertThat(report.<String>read("$.findings[0].type")).isEqualTo("PENDING_REFUND");
        assertThat(report.<String>read("$.findings[0].resolution")).isEqualTo("AUTO_RESOLVED");
        assertThat(jdbc.queryForObject("select status from refunds where id = ?", String.class, refundId)).isEqualTo("FAILED");
        refund(key, paymentId, 10_00L).andExpect(status().isCreated());
    }

    @Test
    void onlyOneRunPerMerchantAtATime() throws Exception {
        String key = newMerchantKey();
        jdbc.update("insert into reconciliation_runs (id, merchant_id, trigger_type, status, started_at) "
                + "values (gen_random_uuid(), ?, 'MANUAL', 'RUNNING', now())", merchantIdOf(key));
        postJson(key, "/api/v1/reconciliation/runs", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_IN_PROGRESS"));
    }
}
