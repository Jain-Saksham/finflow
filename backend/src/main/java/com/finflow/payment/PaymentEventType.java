package com.finflow.payment;

public enum PaymentEventType {
    CREATED,
    AUTHORIZED,
    DECLINED,
    FAILED,
    TIMED_OUT,
    CAPTURED,
    VOIDED,
    REFUNDED,
    /** Non-transition events: an attempt that failed without changing the payment's status. */
    CAPTURE_FAILED,
    VOID_FAILED,
    REFUND_FAILED
}
