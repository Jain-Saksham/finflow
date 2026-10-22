package com.finflow.ledger;

public enum JournalType {
    CAPTURE,
    REFUND,
    /** Correction: mirrors an earlier journal with every direction swapped. Entries are never edited. */
    REVERSAL
}
