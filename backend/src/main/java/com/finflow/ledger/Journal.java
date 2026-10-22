package com.finflow.ledger;

import java.util.List;
import java.util.UUID;

/**
 * One business event expressed as ledger lines. The constructor enforces the double-entry
 * invariant, so an unbalanced journal cannot even be built, let alone persisted.
 */
public record Journal(JournalType type, UUID merchantId, UUID paymentId, UUID refundId, UUID reversesJournalId,
                      String currency, String description, List<Line> lines) {

    public record Line(LedgerAccount account, EntryDirection direction, long amountMinor) {
    }

    public Journal {
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two lines");
        }
        long debits = 0;
        long credits = 0;
        for (Line line : lines) {
            if (line.amountMinor() <= 0) {
                throw new IllegalArgumentException("Ledger amounts must be positive");
            }
            if (line.direction() == EntryDirection.DEBIT) {
                debits = Math.addExact(debits, line.amountMinor());
            } else {
                credits = Math.addExact(credits, line.amountMinor());
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException("Unbalanced journal: debits=" + debits + " credits=" + credits);
        }
        if (type == JournalType.CAPTURE && refundId != null || type == JournalType.REFUND && refundId == null) {
            throw new IllegalArgumentException("Refund journals, and only refund journals, reference a refund");
        }
        if ((type == JournalType.REVERSAL) != (reversesJournalId != null)) {
            throw new IllegalArgumentException("Reversal journals, and only reversal journals, reference a journal");
        }
    }

    /** Capture: the provider now owes us the money, and we owe it to the merchant. */
    public static Journal capture(UUID merchantId, UUID paymentId, long amountMinor, String currency) {
        return new Journal(JournalType.CAPTURE, merchantId, paymentId, null, null, currency, "Payment captured",
                List.of(new Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.DEBIT, amountMinor),
                        new Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.CREDIT, amountMinor)));
    }

    /** Refund: the exact reverse of a capture, for the refunded amount. */
    public static Journal refund(UUID merchantId, UUID paymentId, UUID refundId, long amountMinor, String currency) {
        return new Journal(JournalType.REFUND, merchantId, paymentId, refundId, null, currency, "Payment refunded",
                List.of(new Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.DEBIT, amountMinor),
                        new Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.CREDIT, amountMinor)));
    }

    /** The correction for {@code original}: same accounts and amounts, every direction swapped. */
    public static Journal reversalOf(UUID originalJournalId, List<LedgerEntry> original, String reason) {
        if (original.isEmpty()) {
            throw new IllegalArgumentException("Nothing to reverse");
        }
        LedgerEntry first = original.getFirst();
        List<Line> lines = original.stream()
                .map(e -> new Line(e.getAccount(), e.getDirection() == EntryDirection.DEBIT
                        ? EntryDirection.CREDIT : EntryDirection.DEBIT, e.getAmountMinor()))
                .toList();
        return new Journal(JournalType.REVERSAL, first.getMerchantId(), first.getPaymentId(), first.getRefundId(),
                originalJournalId, first.getCurrency(), "Reversal: " + reason, lines);
    }
}
