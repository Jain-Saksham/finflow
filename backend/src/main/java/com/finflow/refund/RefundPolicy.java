package com.finflow.refund;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.payment.Payment;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Pure refund-eligibility rules, kept separate from persistence so they are trivially unit-testable. */
@Component
public class RefundPolicy {

    @ConfigurationProperties("finflow.refunds")
    public record Properties(int windowDays) {
    }

    private final Duration window;

    public RefundPolicy(Properties properties) {
        this.window = Duration.ofDays(properties.windowDays());
    }

    /**
     * @param pendingMinor refunds already reserved but not yet confirmed by the provider; they count
     *                     against the refundable amount so two concurrent refunds can't both pass
     */
    public void assertEligible(Payment payment, long requestedMinor, long pendingMinor, Instant now) {
        if (!payment.getStatus().isRefundable()) {
            throw new ApiException(ErrorCode.PAYMENT_NOT_REFUNDABLE,
                    "Only captured payments can be refunded; this payment is " + payment.getStatus());
        }
        if (payment.getCapturedAt() != null && payment.getCapturedAt().plus(window).isBefore(now)) {
            throw new ApiException(ErrorCode.REFUND_WINDOW_EXPIRED,
                    "Refunds are allowed up to " + window.toDays() + " days after capture");
        }
        long available = payment.refundableAmountMinor() - pendingMinor;
        if (requestedMinor <= 0 || requestedMinor > available) {
            throw new ApiException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE,
                    "Requested refund of " + requestedMinor + " exceeds the refundable amount of " + Math.max(available, 0));
        }
    }
}
