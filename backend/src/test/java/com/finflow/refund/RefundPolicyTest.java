package com.finflow.refund;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.order.MerchantOrder;
import com.finflow.payment.CaptureMode;
import com.finflow.payment.Payment;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {

    private final RefundPolicy policy = new RefundPolicy(new RefundPolicy.Properties(180));

    private static Payment payment(boolean captured) {
        var order = new MerchantOrder(UUID.randomUUID(), "ORD-1", 100_00, "USD", null);
        Payment p = new Payment(order, "tok_success", CaptureMode.MANUAL, "SIMULATOR", "key-12345678");
        p.authorize("pi_sim_1");
        if (captured) {
            p.capture();
        }
        return p;
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((ApiException) t).code();
    }

    @Test
    void allowsRefundUpToTheRefundableAmount() {
        Payment p = payment(true);
        assertThatCode(() -> policy.assertEligible(p, 100_00, 0, Instant.now())).doesNotThrowAnyException();
    }

    @Test
    void rejectsUncapturedPayments() {
        Payment p = payment(false);
        assertThatThrownBy(() -> policy.assertEligible(p, 1, 0, Instant.now()))
                .matches(t -> codeOf(t) == ErrorCode.PAYMENT_NOT_REFUNDABLE);
    }

    @Test
    void pendingRefundsReduceTheAvailableAmount() {
        Payment p = payment(true);
        assertThatThrownBy(() -> policy.assertEligible(p, 60_00, 50_00, Instant.now()))
                .matches(t -> codeOf(t) == ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        assertThatCode(() -> policy.assertEligible(p, 50_00, 50_00, Instant.now())).doesNotThrowAnyException();
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        Payment p = payment(true);
        assertThatThrownBy(() -> policy.assertEligible(p, 0, 0, Instant.now()))
                .matches(t -> codeOf(t) == ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
    }

    @Test
    void rejectsRefundsAfterTheWindow() {
        Payment p = payment(true);
        Instant later = p.getCapturedAt().plus(Duration.ofDays(181));
        assertThatThrownBy(() -> policy.assertEligible(p, 1_00, 0, later))
                .matches(t -> codeOf(t) == ErrorCode.REFUND_WINDOW_EXPIRED);
    }
}
