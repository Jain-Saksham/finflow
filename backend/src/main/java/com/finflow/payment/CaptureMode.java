package com.finflow.payment;

public enum CaptureMode {
    /** Capture immediately after a successful authorization. */
    AUTOMATIC,
    /** Authorize only; the merchant captures (or voids) later, e.g. when the goods ship. */
    MANUAL
}
