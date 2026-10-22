package com.finflow.reconciliation;

import java.util.UUID;

/**
 * One difference found by {@link ReconciliationMatcher}. {@code action} is the safe automatic fix,
 * or NONE when a human must investigate (anything implying money moved wrongly at the provider).
 */
public record Discrepancy(FindingType type, Severity severity, Action action, UUID paymentId, UUID refundId,
                          String providerReference, String expected, String actual, String detail) {

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum Action {
        NONE,
        /** Look the authorization up at the provider and apply it (PaymentService.resolveFromProvider). */
        RESOLVE_FROM_PROVIDER,
        /** Provider captured, FinFlow still AUTHORIZED: re-run capture, which is idempotent at the provider. */
        APPLY_CAPTURE,
        /** Provider refunded, FinFlow refund PENDING: finish the local half. */
        CONFIRM_REFUND,
        /** Stale PENDING refund the provider never saw: fail it and release the reservation. */
        FAIL_REFUND
    }

    public boolean recoverable() {
        return action != Action.NONE;
    }
}
