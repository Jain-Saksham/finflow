package com.finflow.ledger;

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

/** Append-only: corrections are new journals, never edits. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type", nullable = false)
    private JournalType journalType;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "refund_id")
    private UUID refundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    LedgerEntry(UUID journalId, Journal journal, Journal.Line line, Instant createdAt) {
        this.journalId = journalId;
        this.journalType = journal.type();
        this.merchantId = journal.merchantId();
        this.paymentId = journal.paymentId();
        this.refundId = journal.refundId();
        this.account = line.account();
        this.direction = line.direction();
        this.amountMinor = line.amountMinor();
        this.currency = journal.currency();
        this.description = journal.description();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getJournalId() {
        return journalId;
    }

    public JournalType getJournalType() {
        return journalType;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
