package com.finflow.reconciliation;

public enum FindingType {
    /** Provider has a transaction FinFlow knows nothing about. */
    ORPHAN_PROVIDER_TRANSACTION,
    /** Provider captured the same payment more than once: the customer was charged twice. */
    DUPLICATE_PROVIDER_CAPTURE,
    DUPLICATE_PROVIDER_REFUND,
    /** FinFlow recorded money movement the provider has no record of. */
    MISSING_AT_PROVIDER,
    AMOUNT_MISMATCH,
    /** Both sides know the payment but disagree on its state. */
    STATUS_MISMATCH,
    /** CREATED/TIMED_OUT payment whose outcome was never applied locally (timeout or crash). */
    UNRESOLVED_PAYMENT,
    /** Refund stuck PENDING after the provider call (crash between provider and local commit). */
    PENDING_REFUND,
    /** Ledger movement for a payment disagrees with its captured/refunded amounts. */
    LEDGER_MISMATCH
}
