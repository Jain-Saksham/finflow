package com.finflow.refund;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class RefundDtos {

    private RefundDtos() {
    }

    /** @param amountMinor omit to refund everything that is still refundable */
    public record CreateRefundRequest(@Positive Long amountMinor, @Size(max = 255) String reason) {
    }

    public record RefundResponse(UUID id, UUID paymentId, long amountMinor, String currency, RefundStatus status,
                                 String reason, String providerReference, String failureCode, Instant createdAt,
                                 Instant updatedAt) {

        public static RefundResponse from(Refund r) {
            return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmountMinor(), r.getCurrency(), r.getStatus(),
                    r.getReason(), r.getProviderReference(), r.getFailureCode(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }
}
