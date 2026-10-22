package com.finflow.payment;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle. The allowed-transition table below is the single source of truth; every
 * status change goes through {@link Payment} which consults it.
 *
 * <pre>
 * CREATED ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *    │  ▲          │              └───────────────────────────────►┘
 *    │  │          └──► VOIDED
 *    ├──┼──► DECLINED / FAILED
 *    └──► TIMED_OUT ──► AUTHORIZED / DECLINED / FAILED   (resolved by syncing with the provider)
 * </pre>
 */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    DECLINED,
    FAILED,
    TIMED_OUT,
    VOIDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    /**
     * Statuses that may have moved money (or still could). At most one payment per order may be in
     * one of these; mirrored by the {@code ux_payments_one_active_per_order} partial unique index.
     */
    public static final Set<PaymentStatus> ACTIVE =
            EnumSet.of(CREATED, AUTHORIZED, TIMED_OUT, CAPTURED, PARTIALLY_REFUNDED, REFUNDED);

    static {
        ALLOWED.put(CREATED, EnumSet.of(AUTHORIZED, DECLINED, FAILED, TIMED_OUT));
        ALLOWED.put(TIMED_OUT, EnumSet.of(AUTHORIZED, DECLINED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURED, VOIDED));
        ALLOWED.put(CAPTURED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        // Self-transition: a second partial refund keeps the payment partially refunded.
        ALLOWED.put(PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED));
        for (PaymentStatus terminal : EnumSet.of(REFUNDED, DECLINED, FAILED, VOIDED)) {
            ALLOWED.put(terminal, EnumSet.noneOf(PaymentStatus.class));
        }
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean isRefundable() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED;
    }
}
