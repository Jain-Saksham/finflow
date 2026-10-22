package com.finflow.refund;

import com.finflow.common.persistence.AuditedEntity;
import com.finflow.payment.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    private String reason;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    protected Refund() {
    }

    Refund(Payment payment, long amountMinor, String reason, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.merchantId = payment.getMerchantId();
        this.paymentId = payment.getId();
        this.amountMinor = amountMinor;
        this.currency = payment.getCurrency();
        this.status = RefundStatus.PENDING;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
    }

    void succeed(String providerReference) {
        requirePending();
        this.status = RefundStatus.SUCCEEDED;
        this.providerReference = providerReference;
    }

    void fail(String failureCode) {
        requirePending();
        this.status = RefundStatus.FAILED;
        this.failureCode = failureCode;
    }

    private void requirePending() {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Refund " + id + " is already " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
