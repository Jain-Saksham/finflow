package com.finflow.ledger;

import java.util.List;
import java.util.UUID;

/**
 * One business event expressed as ledger lines. The constructor enforces the double-entry
 * invariant, so an unbalanced journal cannot even be built, let alone persisted.
 */
public record Journal(JournalType type, UUID merchantId, UUID paymentId, UUID refundId,
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
        if ((type == JournalType.REFUND) != (refundId != null)) {
            throw new IllegalArgumentException("Refund journals, and only refund journals, reference a refund");
        }
    }

    /** Capture: the provider now owes us the money, and we owe it to the merchant. */
    public static Journal capture(UUID merchantId, UUID paymentId, long amountMinor, String currency) {
        return new Journal(JournalType.CAPTURE, merchantId, paymentId, null, currency, "Payment captured",
                List.of(new Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.DEBIT, amountMinor),
                        new Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.CREDIT, amountMinor)));
    }

    /** Refund: the exact reverse of a capture, for the refunded amount. */
    public static Journal refund(UUID merchantId, UUID paymentId, UUID refundId, long amountMinor, String currency) {
        return new Journal(JournalType.REFUND, merchantId, paymentId, refundId, currency, "Payment refunded",
                List.of(new Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.DEBIT, amountMinor),
                        new Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.CREDIT, amountMinor)));
    }
}
