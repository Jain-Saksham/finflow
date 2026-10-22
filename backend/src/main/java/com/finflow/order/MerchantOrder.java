package com.finflow.order;

import com.finflow.common.error.ApiException;
import com.finflow.common.error.ErrorCode;
import com.finflow.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A merchant's order. Named MerchantOrder because ORDER is a reserved word in JPQL. */
@Entity
@Table(name = "orders")
public class MerchantOrder extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "merchant_reference", nullable = false, updatable = false)
    private String merchantReference;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private String currency;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    protected MerchantOrder() {
    }

    public MerchantOrder(UUID merchantId, String merchantReference, long amountMinor, String currency, String description) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.description = description;
        this.status = OrderStatus.OPEN;
    }

    public void markPaid() {
        if (status != OrderStatus.OPEN) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Order " + id + " is already " + status);
        }
        status = OrderStatus.PAID;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
