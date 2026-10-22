package com.finflow.order;

public enum OrderStatus {
    /** Awaiting a successful payment; failed or voided attempts leave the order open for retry. */
    OPEN,
    /** A payment for the order has been captured. */
    PAID
}
