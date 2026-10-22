package com.finflow.refund;

public enum RefundStatus {
    /** Reserved against the payment while the provider call is in flight. */
    PENDING,
    SUCCEEDED,
    FAILED
}
