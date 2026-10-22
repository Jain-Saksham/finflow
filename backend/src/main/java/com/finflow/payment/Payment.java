package com.finflow.payment;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.common.persistence.AuditedEntity;
import com.finflow.order.MerchantOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment aggregate. Status only changes through the intention-revealing methods below, each of
 * which validates the transition and returns the history event the caller must persist.
 */
@Entity
@Table(name = "payments")
public class Payment extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, updatable = false)
    private CaptureMode captureMode;

    @Column(name = "payment_method_token", nullable = false, updatable = false)
    private String paymentMethodToken;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    protected Payment() {
    }

    public Payment(MerchantOrder order, String paymentMethodToken, CaptureMode captureMode, String provider,
                   String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.merchantId = order.getMerchantId();
        this.orderId = order.getId();
        this.amountMinor = order.getAmountMinor();
        this.currency = order.getCurrency();
        this.status = PaymentStatus.CREATED;
        this.captureMode = captureMode;
        this.paymentMethodToken = paymentMethodToken;
        this.provider = provider;
        this.idempotencyKey = idempotencyKey;
    }

    public PaymentEvent creationEvent() {
        return new PaymentEvent(this, PaymentEventType.CREATED, null, amountMinor, captureMode + " capture via " + provider);
    }

    public PaymentEvent authorize(String providerReference) {
        PaymentStatus from = transition(PaymentStatus.AUTHORIZED, "authorize");
        this.providerReference = providerReference;
        this.authorizedAt = now();
        return new PaymentEvent(this, PaymentEventType.AUTHORIZED, from, amountMinor, "Provider reference " + providerReference);
    }

    public PaymentEvent decline(String code, String message) {
        PaymentStatus from = transition(PaymentStatus.DECLINED, "decline");
        recordFailure(code, message);
        return new PaymentEvent(this, PaymentEventType.DECLINED, from, null, code + ": " + message);
    }

    public PaymentEvent fail(String code, String message) {
        PaymentStatus from = transition(PaymentStatus.FAILED, "fail");
        recordFailure(code, message);
        return new PaymentEvent(this, PaymentEventType.FAILED, from, null, code + ": " + message);
    }

    public PaymentEvent timeOut(String message) {
        PaymentStatus from = transition(PaymentStatus.TIMED_OUT, "time out");
        recordFailure("provider_timeout", message);
        return new PaymentEvent(this, PaymentEventType.TIMED_OUT, from, null, message);
    }

    public PaymentEvent capture() {
        PaymentStatus from = transition(PaymentStatus.CAPTURED, "capture");
        this.capturedAmountMinor = amountMinor;
        this.capturedAt = now();
        return new PaymentEvent(this, PaymentEventType.CAPTURED, from, amountMinor, null);
    }

    public PaymentEvent voidAuthorization() {
        PaymentStatus from = transition(PaymentStatus.VOIDED, "void");
        return new PaymentEvent(this, PaymentEventType.VOIDED, from, null, "Authorization released");
    }

    public PaymentEvent applyRefund(UUID refundId, long refundAmountMinor) {
        if (refundAmountMinor <= 0 || refundAmountMinor > refundableAmountMinor()) {
            throw new ApiException(ErrorCode.REFUND_EXCEEDS_REFUNDABLE, "Refund of " + refundAmountMinor
                    + " exceeds the refundable amount of " + refundableAmountMinor());
        }
        long refunded = refundedAmountMinor + refundAmountMinor;
        PaymentStatus next = refunded == capturedAmountMinor ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        PaymentStatus from = transition(next, "refund");
        this.refundedAmountMinor = refunded;
        return new PaymentEvent(this, PaymentEventType.REFUNDED, from, refundAmountMinor, "Refund " + refundId);
    }

    /** Records a failed attempt that leaves the status unchanged (e.g. provider down during capture). */
    public PaymentEvent attemptFailed(PaymentEventType type, String detail) {
        return new PaymentEvent(this, type, status, null, detail);
    }

    public void assertCan(PaymentStatus next, String action) {
        if (!status.canTransitionTo(next)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot " + action + " a payment in status " + status);
        }
    }

    public long refundableAmountMinor() {
        return capturedAmountMinor - refundedAmountMinor;
    }

    private PaymentStatus transition(PaymentStatus next, String action) {
        assertCan(next, action);
        PaymentStatus from = status;
        status = next;
        return from;
    }

    private void recordFailure(String code, String message) {
        this.failureCode = code;
        this.failureMessage = message == null || message.length() <= 255 ? message : message.substring(0, 255);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public CaptureMode getCaptureMode() {
        return captureMode;
    }

    public String getPaymentMethodToken() {
        return paymentMethodToken;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public long getCapturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
