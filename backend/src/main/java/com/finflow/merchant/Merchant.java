package com.finflow.merchant;

import com.finflow.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant extends AuditedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** SHA-256 of the API key; the plaintext key is never stored. */
    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    /** First characters of the key, safe to display so users can tell keys apart. */
    @Column(name = "api_key_prefix", nullable = false)
    private String apiKeyPrefix;

    @Column(name = "default_currency", nullable = false)
    private String defaultCurrency;

    @Column(nullable = false)
    private boolean demo;

    protected Merchant() {
    }

    public Merchant(String name, String defaultCurrency, boolean demo, String apiKey) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.defaultCurrency = defaultCurrency;
        this.demo = demo;
        rotateApiKey(apiKey);
    }

    public void rotateApiKey(String apiKey) {
        this.apiKeyHash = ApiKeys.hash(apiKey);
        this.apiKeyPrefix = ApiKeys.displayPrefix(apiKey);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public boolean isDemo() {
        return demo;
    }
}
