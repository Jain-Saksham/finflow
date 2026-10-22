package com.finflow.provider;

import java.time.Instant;
import java.util.UUID;

/** One row of the provider's independent transaction records. */
public record ProviderTransaction(long id, Type type, Status status, UUID merchantId, UUID paymentId, UUID refundId,
                                  String providerReference, long amountMinor, String currency, String declineCode,
                                  String injectedAnomaly, Instant createdAt) {

    public enum Type { AUTHORIZATION, CAPTURE, VOID, REFUND }

    public enum Status { SUCCEEDED, DECLINED }
}
