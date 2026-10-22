package com.finflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.order.MerchantOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

    static Payment newPayment(long amount, CaptureMode mode) {
        var order = new MerchantOrder(UUID.randomUUID(), "ORD-1", amount, "USD", null);
        return new Payment(order, "tok_success", mode, "SIMULATOR", "key-12345678");
    }

    static Payment capturedPayment(long amount) {
        Payment p = newPayment(amount, CaptureMode.AUTOMATIC);
        p.authorize("pi_sim_1");
        p.capture();
        return p;
    }

    @Test
    void happyPathRecordsEventsWithFromAndToStatus() {
        Payment p = newPayment(10_00, CaptureMode.MANUAL);

        PaymentEvent authorized = p.authorize("pi_sim_1");
        assertThat(authorized.getFromStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(authorized.getToStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(p.getProviderReference()).isEqualTo("pi_sim_1");

        PaymentEvent captured = p.capture();
        assertThat(captured.getToStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getCapturedAmountMinor()).isEqualTo(10_00);
        assertThat(p.refundableAmountMinor()).isEqualTo(10_00);
    }

    @Test
    void cannotCaptureADeclinedPayment() {
        Payment p = newPayment(10_00, CaptureMode.AUTOMATIC);
        p.decline("insufficient_funds", "Insufficient funds");

        assertThatThrownBy(p::capture)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void cannotCaptureTwiceOrCaptureAfterVoid() {
        Payment captured = capturedPayment(5_00);
        assertThatThrownBy(captured::capture).isInstanceOf(ApiException.class);

        Payment voided = newPayment(5_00, CaptureMode.MANUAL);
        voided.authorize("pi_sim_2");
        voided.voidAuthorization();
        assertThatThrownBy(voided::capture).isInstanceOf(ApiException.class);
    }

    @Test
    void partialThenFullRefundMovesThroughRefundStatuses() {
        Payment p = capturedPayment(100_00);

        p.applyRefund(UUID.randomUUID(), 30_00);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(p.refundableAmountMinor()).isEqualTo(70_00);

        p.applyRefund(UUID.randomUUID(), 20_00);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        p.applyRefund(UUID.randomUUID(), 50_00);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.refundableAmountMinor()).isZero();
    }

    @Test
    void refundCannotExceedRefundableAmount() {
        Payment p = capturedPayment(10_00);

        assertThatThrownBy(() -> p.applyRefund(UUID.randomUUID(), 10_01))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        assertThat(p.getRefundedAmountMinor()).isZero();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void timedOutPaymentCanBeResolvedToAuthorized() {
        Payment p = newPayment(10_00, CaptureMode.AUTOMATIC);
        p.timeOut("no response");
        assertThat(p.getFailureCode()).isEqualTo("provider_timeout");

        p.authorize("pi_sim_late");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void availableActionsFollowTheStateMachine() {
        Payment p = newPayment(10_00, CaptureMode.MANUAL);
        assertThat(PaymentAction.availableFor(p)).isEmpty();

        p.authorize("pi_sim_1");
        assertThat(PaymentAction.availableFor(p)).containsExactly(PaymentAction.CAPTURE, PaymentAction.VOID);

        p.capture();
        assertThat(PaymentAction.availableFor(p)).containsExactly(PaymentAction.REFUND);

        p.applyRefund(UUID.randomUUID(), 10_00);
        assertThat(PaymentAction.availableFor(p)).isEmpty();

        Payment timedOut = newPayment(10_00, CaptureMode.AUTOMATIC);
        timedOut.timeOut("no response");
        assertThat(PaymentAction.availableFor(timedOut)).containsExactly(PaymentAction.SYNC);
    }
}
