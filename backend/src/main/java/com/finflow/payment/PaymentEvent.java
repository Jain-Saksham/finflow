package com.finflow.payment;

import com.finflow.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One immutable row of a payment's transaction history. */
@Entity
@Immutable
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PaymentStatus toStatus;

    @Column(name = "amount_minor")
    private Long amountMinor;

    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentEvent() {
    }

    PaymentEvent(Payment payment, PaymentEventType type, PaymentStatus fromStatus, Long amountMinor, String detail) {
        this.paymentId = payment.getId();
        this.merchantId = payment.getMerchantId();
        this.type = type;
        this.fromStatus = fromStatus;
        this.toStatus = payment.getStatus();
        this.amountMinor = amountMinor;
        this.detail = detail == null || detail.length() <= 255 ? detail : detail.substring(0, 255);
        this.createdAt = AuditedEntity.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public PaymentEventType getType() {
        return type;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
