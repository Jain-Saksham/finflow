package com.finflow.payment;

import java.util.ArrayList;
import java.util.List;

/** Actions the API will currently accept for a payment, so clients don't duplicate the state machine. */
public enum PaymentAction {
    CAPTURE, VOID, REFUND, SYNC;

    public static List<PaymentAction> availableFor(Payment payment) {
        PaymentStatus status = payment.getStatus();
        List<PaymentAction> actions = new ArrayList<>();
        if (status.canTransitionTo(PaymentStatus.CAPTURED)) {
            actions.add(CAPTURE);
        }
        if (status.canTransitionTo(PaymentStatus.VOIDED)) {
            actions.add(VOID);
        }
        if (status.isRefundable() && payment.refundableAmountMinor() > 0) {
            actions.add(REFUND);
        }
        if (status == PaymentStatus.TIMED_OUT) {
            actions.add(SYNC);
        }
        return actions;
    }
}
