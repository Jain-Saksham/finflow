package com.finflow.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Audit timestamps plus an optimistic-lock version for mutable aggregates.
 * A null version also tells Spring Data the entity is new, so app-assigned UUIDs don't trigger
 * a select-before-insert.
 */
@MappedSuperclass
public abstract class AuditedEntity {

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = now();
    }

    /** PostgreSQL stores microseconds; truncating keeps in-memory and reloaded values identical. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
