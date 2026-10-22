package com.finflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class PaymentFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void successfulAutomaticPaymentIsCapturedAndPersistedWithHistoryAndLedger() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 25_00);

        MvcResult created = pay(key, orderId, "tok_success", "AUTOMATIC")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(25_00))
                .andExpect(jsonPath("$.providerReference").isNotEmpty())
                .andExpect(jsonPath("$.availableActions", contains("REFUND")))
                .andReturn();
        String paymentId = json(created).read("$.id");

        getJson(key, "/api/v1/payments/" + paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        getJson(key, "/api/v1/payments/" + paymentId + "/events")
                .andExpect(jsonPath("$[*].type", contains("CREATED", "AUTHORIZED", "CAPTURED")))
                .andExpect(jsonPath("$[*].toStatus", contains("CREATED", "AUTHORIZED", "CAPTURED")));
        getJson(key, "/api/v1/orders/" + orderId).andExpect(jsonPath("$.status").value("PAID"));
        getJson(key, "/api/v1/ledger/entries?paymentId=" + paymentId)
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].direction", org.hamcrest.Matchers.containsInAnyOrder("DEBIT", "CREDIT")));
    }

    @Test
    void declinedPaymentKeepsOrderOpenAndAllowsRetry() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 10_00);

        pay(key, orderId, "tok_decline_insufficient_funds", "AUTOMATIC")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.failureCode").value("insufficient_funds"))
                .andExpect(jsonPath("$.availableActions", hasSize(0)));
        getJson(key, "/api/v1/orders/" + orderId).andExpect(jsonPath("$.status").value("OPEN"));

        pay(key, orderId, "tok_success", "AUTOMATIC").andExpect(jsonPath("$.status").value("CAPTURED"));
        getJson(key, "/api/v1/payments?orderId=" + orderId).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void manualCaptureFlowAndInvalidTransitionsAreRejected() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 40_00);

        DocumentContext authorized = payAndRead(key, orderId, "tok_success", "MANUAL");
        assertThat(authorized.<String>read("$.status")).isEqualTo("AUTHORIZED");
        assertThat(authorized.<List<String>>read("$.availableActions")).containsExactly("CAPTURE", "VOID");
        String paymentId = authorized.read("$.id");

        // Not captured yet: nothing to refund.
        refund(key, paymentId, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));

        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        postJson(key, "/api/v1/payments/" + paymentId + "/void", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void voidedPaymentCannotBeCapturedAndOrderCanBePaidAgain() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 15_00);
        String paymentId = payAndRead(key, orderId, "tok_success", "MANUAL").read("$.id");

        // While authorized, the order already has an active payment.
        pay(key, orderId, "tok_success", "AUTOMATIC")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_HAS_ACTIVE_PAYMENT"));

        postJson(key, "/api/v1/payments/" + paymentId + "/void", null)
                .andExpect(jsonPath("$.status").value("VOIDED"));
        postJson(key, "/api/v1/payments/" + paymentId + "/capture", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        pay(key, orderId, "tok_success", "AUTOMATIC").andExpect(jsonPath("$.status").value("CAPTURED"));
        pay(key, orderId, "tok_success", "AUTOMATIC")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
    }

    @Test
    void partialAndFullRefundsUpdateStatusLedgerAndRejectOverRefund() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_00);
        String paymentId = payAndRead(key, orderId, "tok_success", "AUTOMATIC").read("$.id");

        refund(key, paymentId, 30_00L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.providerReference").isNotEmpty());
        getJson(key, "/api/v1/payments/" + paymentId)
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundableAmountMinor").value(70_00));

        refund(key, paymentId, 70_01L)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_REFUNDABLE"));

        // Omitting the amount refunds the remainder.
        refund(key, paymentId, null).andExpect(jsonPath("$.amountMinor").value(70_00));
        getJson(key, "/api/v1/payments/" + paymentId)
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmountMinor").value(100_00));

        refund(key, paymentId, 1L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_REFUNDABLE"));

        getJson(key, "/api/v1/payments/" + paymentId + "/refunds").andExpect(jsonPath("$", hasSize(2)));
        getJson(key, "/api/v1/ledger/entries?paymentId=" + paymentId).andExpect(jsonPath("$.totalElements").value(6));
        getJson(key, "/api/v1/ledger/balances")
                .andExpect(jsonPath("$.checks[0].balanced").value(true))
                .andExpect(jsonPath("$.accounts[?(@.account=='MERCHANT_BALANCE')].balanceMinor").value(0));
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalPaymentInsteadOfChargingTwice() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 12_00);
        String idem = UUID.randomUUID().toString();
        String body = "{\"orderId\":\"" + orderId + "\",\"paymentMethodToken\":\"tok_success\"}";

        String firstId = json(postJson(key, "/api/v1/payments", body, idem)
                .andExpect(status().isCreated()).andReturn()).read("$.id");
        postJson(key, "/api/v1/payments", body, idem)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstId));

        String otherOrder = createOrder(key, 12_00);
        postJson(key, "/api/v1/payments", "{\"orderId\":\"" + otherOrder + "\",\"paymentMethodToken\":\"tok_success\"}", idem)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        getJson(key, "/api/v1/payments?orderId=" + orderId).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void timedOutPaymentIsResolvedBySyncingWithTheProvider() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 30_00);

        DocumentContext timedOut = payAndRead(key, orderId, "tok_timeout", "AUTOMATIC");
        assertThat(timedOut.<String>read("$.status")).isEqualTo("TIMED_OUT");
        assertThat(timedOut.<List<String>>read("$.availableActions")).containsExactly("SYNC");
        String paymentId = timedOut.read("$.id");

        // Outcome unknown, so a second payment for the order is blocked (no double charge).
        pay(key, orderId, "tok_success", "AUTOMATIC")
                .andExpect(jsonPath("$.code").value("ORDER_HAS_ACTIVE_PAYMENT"));

        postJson(key, "/api/v1/payments/" + paymentId + "/sync", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        postJson(key, "/api/v1/payments/" + paymentId + "/sync", null)
                .andExpect(status().isConflict());
    }

    @Test
    void providerUnavailableFailsSafelyAndAllowsRetry() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 8_00);

        pay(key, orderId, "tok_provider_unavailable", "AUTOMATIC")
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("provider_unavailable"));
        pay(key, orderId, "tok_success", "AUTOMATIC").andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void dashboardReflectsRealPaymentData() throws Exception {
        String key = newMerchantKey();
        pay(key, createOrder(key, 50_00), "tok_success", "AUTOMATIC");
        pay(key, createOrder(key, 20_00), "tok_decline_do_not_honor", "AUTOMATIC");

        getJson(key, "/api/v1/dashboard/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPayments").value(2))
                .andExpect(jsonPath("$.totalOrders").value(2))
                .andExpect(jsonPath("$.statusCounts.CAPTURED").value(1))
                .andExpect(jsonPath("$.statusCounts.DECLINED").value(1))
                .andExpect(jsonPath("$.authorizationRatePercent").value(50.0))
                .andExpect(jsonPath("$.volumes[0].capturedMinor").value(50_00))
                .andExpect(jsonPath("$.failureReasons[0].code").value("do_not_honor"))
                .andExpect(jsonPath("$.daily", hasSize(14)))
                .andExpect(jsonPath("$.daily[13].succeeded").value(1))
                .andExpect(jsonPath("$.recentPayments", hasSize(2)));
    }
}
