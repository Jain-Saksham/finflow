package com.finflow.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(
            @NotNull UUID orderId,
            @NotBlank @Pattern(regexp = "^tok_[a-z_]{1,60}$",
                    message = "must be a simulator token such as tok_success (card numbers are never accepted)")
            String paymentMethodToken,
            CaptureMode captureMode) {

        public CaptureMode captureModeOrDefault() {
            return captureMode == null ? CaptureMode.AUTOMATIC : captureMode;
        }
    }

    public record PaymentResponse(UUID id, UUID orderId, long amountMinor, String currency, PaymentStatus status,
                                  CaptureMode captureMode, String paymentMethodToken, String provider,
                                  String providerReference, String failureCode, String failureMessage,
                                  long capturedAmountMinor, long refundedAmountMinor, long refundableAmountMinor,
                                  List<PaymentAction> availableActions, Instant authorizedAt, Instant capturedAt,
                                  Instant createdAt, Instant updatedAt) {

        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getAmountMinor(), p.getCurrency(), p.getStatus(),
                    p.getCaptureMode(), p.getPaymentMethodToken(), p.getProvider(), p.getProviderReference(),
                    p.getFailureCode(), p.getFailureMessage(), p.getCapturedAmountMinor(), p.getRefundedAmountMinor(),
                    p.refundableAmountMinor(), PaymentAction.availableFor(p), p.getAuthorizedAt(), p.getCapturedAt(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record PaymentEventResponse(long id, PaymentEventType type, PaymentStatus fromStatus, PaymentStatus toStatus,
                                       Long amountMinor, String detail, Instant createdAt) {

        public static PaymentEventResponse from(PaymentEvent e) {
            return new PaymentEventResponse(e.getId(), e.getType(), e.getFromStatus(), e.getToStatus(),
                    e.getAmountMinor(), e.getDetail(), e.getCreatedAt());
        }
    }
}
