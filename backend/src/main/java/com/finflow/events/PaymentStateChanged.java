package com.finflow.events;

import java.util.UUID;

/** In-process Spring event fired inside the transaction that changed a payment (used for cache eviction). */
public record PaymentStateChanged(UUID merchantId, UUID paymentId) {
}
