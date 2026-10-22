package com.finflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.merchant.MerchantRepository;
import com.finflow.payment.PaymentRepository;
import com.finflow.payment.PaymentStatus;
import com.finflow.refund.RefundDtos.CreateRefundRequest;
import com.finflow.refund.RefundService;
import com.finflow.refund.RefundStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    RefundService refundService;

    @Autowired
    PaymentRepository payments;

    @Autowired
    MerchantRepository merchantRepository;

    @Test
    void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndRead(key, createOrder(key, 100_00), "tok_success", "AUTOMATIC").read("$.id");
        UUID merchantId = payments.findById(UUID.fromString(paymentId)).orElseThrow().getMerchantId();

        int attempts = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        // Each asks for 40.00 of a 100.00 payment: at most two can succeed.
                        return refundService.create(merchantId, UUID.fromString(paymentId),
                                UUID.randomUUID().toString(), new CreateRefundRequest(40_00L, null)).refund().getStatus();
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            start.countDown();
        }

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : results) {
            outcomes.add(f.get());
        }
        assertThat(outcomes).filteredOn(o -> o == RefundStatus.SUCCEEDED).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o == ErrorCode.REFUND_EXCEEDS_REFUNDABLE).hasSize(attempts - 2);

        var payment = payments.findById(UUID.fromString(paymentId)).orElseThrow();
        assertThat(payment.getRefundedAmountMinor()).isEqualTo(80_00);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }
}
