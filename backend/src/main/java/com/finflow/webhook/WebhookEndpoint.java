package com.finflow.webhook;

import com.finflow.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private String url;

    /**
     * Needed in plaintext to sign payloads (HMAC is symmetric), unlike API keys which are hashed.
     * A production system would encrypt it with a KMS-managed key.
     */
    @Column(nullable = false, updatable = false)
    private String secret;

    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "simulated_behavior")
    private SimulatedBehavior simulatedBehavior;

    protected WebhookEndpoint() {
    }

    WebhookEndpoint(UUID id, UUID merchantId, String url, String secret, String description,
                    SimulatedBehavior simulatedBehavior) {
        this.id = id;
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.description = description;
        this.enabled = true;
        this.simulatedBehavior = simulatedBehavior;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public SimulatedBehavior getSimulatedBehavior() {
        return simulatedBehavior;
    }
}
